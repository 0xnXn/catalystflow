package com.catalystflow.user;

import com.catalystflow.user.api.UsersApi;
import com.catalystflow.user.model.CreateUserRequest;
import com.catalystflow.user.model.MessageResponse;
import com.catalystflow.user.model.PublicUser;
import com.catalystflow.user.model.UpdateUserRequest;
import com.catalystflow.user.model.User;
import com.catalystflow.user.model.UserListResponse;
import com.catalystflow.user.service.UserService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * User Service JAX-RS resource.
 *
 * Implements the {@link UsersApi} interface generated from {@code specs/openapi.yaml}.
 * All model classes (User, CreateUserRequest, etc.) are also generated from the spec.
 *
 * Routes:
 *   GET    /users/health            — health check (public, no auth)
 *   GET    /users                   — list all users, optional ?role= filter (ADMIN only)
 *   POST   /users                   — create user in Cognito + DynamoDB (ADMIN only)
 *   GET    /users/{userId}          — get a single user profile
 *   PUT    /users/{userId}          — update user (ADMIN only)
 *   GET    /users/{userId}/manager  — get the manager of a user
 *   GET    /users/{userId}/team     — get direct reports of a manager
 *   GET    /users/{userId}/public   — minimal name/role projection (any authenticated caller)
 *
 * Authentication: JWT decoded by {@link AuthFilter} into {@link ClaimsContext}.
 * API Gateway validates the JWT signature before this code ever runs.
 */
@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource extends BaseResource implements UsersApi {

    private static final Logger LOG = Logger.getLogger(UserResource.class);

    private static final List<String> VALID_ROLES = List.of("ADMIN", "MANAGER", "EMPLOYEE");
    private static final List<String> VALID_STATUSES = List.of("Active", "Archived");

    // Names must start with a (Unicode) letter, then allow letters, combining
    // marks (accents), spaces, hyphens, apostrophes (straight + curly) and
    // periods — covers surnames like O'Brien, Smith-Jones, Van Dyke, García,
    // St. John. The old [a-zA-Z]+ rejected all of these.
    private static final String NAME_PATTERN = "\\p{L}[\\p{L}\\p{M} .'’-]*";

    /**
     * Business "today" for hire-date validation. Lambda runs UTC; hire-date semantics
     * are Sydney-local. Mirrors {@code ReviewCycleResource.AU_ZONE}.
     */
    static final ZoneId AU_ZONE = ZoneId.of("Australia/Sydney");

    @Inject
    UserService userService;

    // ── Health ────────────────────────────────────────────────────────────────

    @Override
    @GET
    @Path("/health")
    public Response healthCheck() {
        return Response.ok(new MessageResponse().message("User Service is Online")).build();
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @Override
    @GET
    public Response listUsers(@QueryParam("role") String role) {
        if (!isAdmin()) return forbidden();

        if (role != null && !VALID_ROLES.contains(role.toUpperCase())) {
            return badRequest("Invalid role filter. Must be ADMIN, MANAGER, or EMPLOYEE");
        }

        List<User> users = role != null
                ? userService.listUsersByRole(role.toUpperCase())
                : userService.listUsers();

        return Response.ok(new UserListResponse().users(users)).build();
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @Override
    @POST
    public Response createUser(CreateUserRequest request) {
        if (!isAdmin()) return forbidden();

        String validationError = validateCreateRequest(request);
        if (validationError != null) return badRequest(validationError);

        try {
            User created = userService.createUser(request);
            LOG.infof("User created: %s by admin: %s", created.getUserId(), getUserId());
            return Response.status(201)
                    .entity(new MessageResponse().message("User created").userId(created.getUserId()))
                    .build();
        } catch (UsernameExistsException e) {
            // Neutral message: don't confirm to the caller whether the email is registered.
            LOG.warnf("Duplicate user creation attempted: email=%s by admin=%s",
                    request.getEmail(), getUserId());
            return conflict("Cannot create user with the supplied details");
        } catch (Exception e) {
            // Generic message: never echo e.getMessage() — it can leak SDK internals.
            LOG.errorf(e, "Failed to create user: email=%s", request.getEmail());
            return serverError("Failed to create user");
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Override
    @GET
    @Path("/{userId}")
    public Response getUser(@PathParam("userId") String userId) {
        Optional<User> user = userService.getUser(userId);
        if (user.isEmpty()) return notFound("User not found");

        // Mirrors the gate on /users/{userId}/manager. Without this, any
        // authenticated employee can swap in another user's UUID and pull
        // their full record (email, hireDate, reportingTo, etc). This 403
        // gate IS the security control — once a caller passes it (self /
        // admin / direct-report's-manager), they're authorized to see the
        // full record. PII stripping inside an authorized lookup would
        // break legitimate uses: review-form reads `seniority` for the AI
        // suggest payload (self path); dashboard and my-team render
        // `email` / `hireDate` for managers viewing direct reports.
        String callerId = getUserId();
        if (!isAdmin()) {
            boolean isSelf = userId.equals(callerId);
            boolean isDirectReport = isManager() && callerId.equals(user.get().getReportingTo());
            if (!isSelf && !isDirectReport) return forbidden();
        }

        return Response.ok(user.get()).build();
    }

    // ── Get Manager ──────────────────────────────────────────────────────────

    @Override
    @GET
    @Path("/{userId}/manager")
    public Response getUserManager(@PathParam("userId") String userId) {
        String callerId = getUserId();

        Optional<User> targetUser = userService.getUser(userId);
        if (targetUser.isEmpty()) return notFound("User not found");

        if (!isAdmin()) {
            boolean isSelf = userId.equals(callerId);
            boolean isDirectReport = isManager() && callerId.equals(targetUser.get().getReportingTo());
            if (!isSelf && !isDirectReport) return forbidden();
        }

        String managerId = targetUser.get().getReportingTo();
        if (managerId == null || managerId.isBlank()) {
            return notFound("This user has no manager assigned");
        }

        Optional<User> manager = userService.getUser(managerId);
        if (manager.isEmpty()) return notFound("Manager not found");

        LOG.infof("Returning manager %s for user %s (requested by %s)", managerId, userId, callerId);
        return Response.ok(manager.get()).build();
    }

    // ── Get Public Profile ───────────────────────────────────────────────────

    // Minimal projection (userId, firstName, lastName, role) any authenticated
    // caller may read. Exists so non-admins can resolve a display name (e.g.
    // an employee rendering their own manager on the dashboard) without
    // unlocking the full /users/{userId} record, which is gated to
    // self / direct-report's-manager / admin. Deliberately omits email,
    // hireDate, reportingTo, seniority, discipline — anything PII or
    // org-structural belongs behind the stricter gate.
    @Override
    @GET
    @Path("/{userId}/public")
    public Response getUserPublic(@PathParam("userId") String userId) {
        Optional<User> user = userService.getUser(userId);
        if (user.isEmpty()) return notFound("User not found");

        User u = user.get();
        PublicUser projection = new PublicUser()
                .userId(u.getUserId())
                .firstName(u.getFirstName())
                .lastName(u.getLastName())
                .role(u.getRole());

        return Response.ok(projection).build();
    }

    // ── Get Team ──────────────────────────────────────────────────────────────

    @Override
    @GET
    @Path("/{userId}/team")
    public Response getManagerTeam(@PathParam("userId") String userId) {
        String callerId = getUserId();

        if (!isAdmin()) {
            if (!isManager()) return forbidden();
            if (!userId.equals(callerId)) return forbidden();
        }

        try {
            List<User> team = userService.getTeam(userId);
            LOG.infof("Returning team of %d for manager %s (requested by %s)", team.size(), userId, callerId);
            return Response.ok(new UserListResponse().users(team).count(team.size())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to get team for manager: %s", userId);
            return serverError("Failed to retrieve team");
        }
    }

    // ── Update (ADMIN only) ───────────────────────────────────────────────────

    @Override
    @PUT
    @Path("/{userId}")
    public Response updateUser(@PathParam("userId") String userId, UpdateUserRequest request) {
        if (!isAdmin()) return forbidden();

        String validationError = validateUpdateRequest(request);
        if (validationError != null) return badRequest(validationError);

        try {
            userService.updateUser(userId, request);
            return Response.ok(new MessageResponse().message("User updated")).build();
        } catch (NoSuchElementException e) {
            return notFound("User not found");
        } catch (Exception e) {
            LOG.errorf(e, "Failed to update user: %s", userId);
            return badRequest("Failed to update user: " + e.getMessage());
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private String validateCreateRequest(CreateUserRequest request) {
        if (request == null) return "Request body is required";

        if (request.getFirstName() == null || request.getFirstName().isBlank())
            return "'firstName' is required";
        if (request.getFirstName().length() > 50)
            return "'firstName' must be 50 characters or less";
        if (!request.getFirstName().matches(NAME_PATTERN))
            return "'firstName' must contain alphabetic characters only";

        if (request.getLastName() == null || request.getLastName().isBlank())
            return "'lastName' is required";
        if (request.getLastName().length() > 50)
            return "'lastName' must be 50 characters or less";
        if (!request.getLastName().matches(NAME_PATTERN))
            return "'lastName' must contain alphabetic characters only";

        if (request.getEmail() == null || request.getEmail().isBlank())
            return "'email' is required";
        if (request.getEmail().contains(" "))
            return "'email' must not contain spaces";
        if (!request.getEmail().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            return "'email' must be a valid email address";

        if (request.getRole() == null || request.getRole().isBlank())
            return "'role' is required (ADMIN, MANAGER, or EMPLOYEE)";
        if (!VALID_ROLES.contains(request.getRole().toUpperCase()))
            return "'role' must be ADMIN, MANAGER, or EMPLOYEE";

        if (request.getPosition() == null || request.getPosition().isBlank())
            return "'position' is required";
        if (request.getPosition().length() > 100)
            return "'position' must be 100 characters or less";

        if (request.getStatus() == null || request.getStatus().isBlank())
            return "'status' is required (Active or Archived)";
        if (!VALID_STATUSES.contains(request.getStatus()))
            return "'status' must be Active or Archived";

        if (request.getSeniority() == null || request.getSeniority().isBlank())
            return "'seniority' is required";
        if (request.getDiscipline() == null || request.getDiscipline().isBlank())
            return "'discipline' is required";

        String hireDateError = validateHireDate(request.getHireDate(), LocalDate.now(AU_ZONE));
        if (hireDateError != null) return hireDateError;

        return null;
    }

    private String validateUpdateRequest(UpdateUserRequest request) {
        if (request == null) return "Request body is required";

        if (request.getFirstName() != null) {
            if (request.getFirstName().isBlank())
                return "'firstName' is required";
            if (request.getFirstName().length() > 50)
                return "'firstName' must be 50 characters or less";
            if (!request.getFirstName().matches(NAME_PATTERN))
                return "'firstName' must contain alphabetic characters only";
        }

        if (request.getLastName() != null) {
            if (request.getLastName().isBlank())
                return "'lastName' is required";
            if (request.getLastName().length() > 50)
                return "'lastName' must be 50 characters or less";
            if (!request.getLastName().matches(NAME_PATTERN))
                return "'lastName' must contain alphabetic characters only";
        }

        if (request.getRole() != null) {
            if (request.getRole().isBlank())
                return "'role' is required (ADMIN, MANAGER, or EMPLOYEE)";
            if (!VALID_ROLES.contains(request.getRole().toUpperCase()))
                return "'role' must be ADMIN, MANAGER, or EMPLOYEE";
        }

        if (request.getPosition() != null) {
            if (request.getPosition().isBlank())
                return "'position' is required";
            if (request.getPosition().length() > 100)
                return "'position' must be 100 characters or less";
        }

        if (request.getStatus() != null) {
            if (request.getStatus().isBlank())
                return "'status' is required (Active or Archived)";
            if (!VALID_STATUSES.contains(request.getStatus()))
                return "'status' must be Active or Archived";
        }

        // seniority/discipline are required fields on every User record. Partial
        // updates may omit them entirely, but if the field is present in the
        // payload it must not be blank — blanking would corrupt downstream AI flows.
        if (request.getSeniority() != null && request.getSeniority().isBlank())
            return "'seniority' must not be blank";
        if (request.getDiscipline() != null && request.getDiscipline().isBlank())
            return "'discipline' must not be blank";

        String hireDateError = validateHireDate(request.getHireDate(), LocalDate.now(AU_ZONE));
        if (hireDateError != null) return hireDateError;

        return null;
    }

    /**
     * Validates a hire-date string against a caller-supplied "today".
     * Package-private + static so unit tests can pass a deterministic date
     * without juggling the system clock.
     *
     * @return null when valid, an error message otherwise
     */
    static String validateHireDate(String hireDate, LocalDate today) {
        if (hireDate == null || hireDate.isBlank()) return null;
        try {
            LocalDate hire = LocalDate.parse(hireDate);
            if (hire.isAfter(today)) {
                return "'hireDate' cannot be a future date";
            }
        } catch (DateTimeParseException e) {
            return "'hireDate' must be a valid date in format YYYY-MM-DD";
        }
        return null;
    }
}

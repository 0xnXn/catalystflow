package com.catalystflow.user;

import com.catalystflow.user.model.CreateUserRequest;
import com.catalystflow.user.model.User;
import com.catalystflow.user.service.UserService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameExistsException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for UserResource.
 *
 * RestAssured makes real HTTP calls to the running Quarkus server.
 * ClaimsContext is mocked so tests can simulate any user identity
 * without needing a real JWT — the JWT verification happens in API Gateway,
 * not in this Lambda.
 */
@QuarkusTest
class UserResourceTest {

    @InjectMock
    UserService userService;

    @InjectMock
    ClaimsContext claimsContext;

    @BeforeEach
    void setUp() {
        reset(userService);
        // Default: unauthenticated — individual tests override as needed
        when(claimsContext.isAdmin()).thenReturn(false);
        when(claimsContext.isManager()).thenReturn(false);
        when(claimsContext.getUserId()).thenReturn("");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void asAdmin() {
        when(claimsContext.isAdmin()).thenReturn(true);
        when(claimsContext.isManager()).thenReturn(false);
        when(claimsContext.getUserId()).thenReturn("admin-id");
    }

    private void asManager() {
        when(claimsContext.isAdmin()).thenReturn(false);
        when(claimsContext.isManager()).thenReturn(true);
        when(claimsContext.getUserId()).thenReturn("mgr-id");
    }

    private void asUser() {
        when(claimsContext.isAdmin()).thenReturn(false);
        when(claimsContext.isManager()).thenReturn(false);
        when(claimsContext.getUserId()).thenReturn("user-id");
    }

    private User makeUser(String userId, String email, String firstName, String lastName, String role) {
        return new User()
                .userId(userId)
                .email(email)
                .firstName(firstName)
                .lastName(lastName)
                .role(role)
                .position("Engineer")
                .status("Active");
    }

    // ── Health ────────────────────────────────────────────────────────────────

    @Test
    void testHealthCheck() {
        given()
            .when().get("/users/health")
            .then()
            .statusCode(200)
            .body("message", containsString("User Service is Online"));
    }

    // ── List users ────────────────────────────────────────────────────────────

    @Test
    void testListUsers_Admin_Returns200() {
        asAdmin();
        when(userService.listUsers()).thenReturn(List.of(
                makeUser("u1", "admin@example.com", "Admin", "User", "ADMIN")
        ));

        given()
            .when().get("/users")
            .then()
            .statusCode(200)
            .body("users.size()", equalTo(1))
            .body("users[0].userId", equalTo("u1"))
            .body("users[0].email", equalTo("admin@example.com"));
    }

    @Test
    void testListUsers_NonAdmin_Returns403() {
        asUser();

        given()
            .when().get("/users")
            .then()
            .statusCode(403);

        verify(userService, never()).listUsers();
    }

    // ── Create user ───────────────────────────────────────────────────────────

    @Test
    void testCreateUser_Admin_Returns201() {
        asAdmin();
        User created = makeUser("new-sub", "new@example.com", "New", "User", "EMPLOYEE");
        when(userService.createUser(any())).thenReturn(created);

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "email", "new@example.com",
                "firstName", "New",
                "lastName", "User",
                "role", "EMPLOYEE",
                "position", "Engineer",
                "status", "Active",
                "seniority", "Engineer",
                "discipline", "Software & DevOps Engineering"
            ))
            .when().post("/users")
            .then()
            .statusCode(201)
            .body("message", equalTo("User created"))
            .body("userId", equalTo("new-sub"));

        verify(userService, times(1)).createUser(any());
    }

    @Test
    void testCreateUser_NonAdmin_Returns403() {
        asUser();

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "email", "new@example.com",
                "firstName", "New",
                "lastName", "User",
                "role", "EMPLOYEE",
                "position", "Engineer",
                "status", "Active"
            ))
            .when().post("/users")
            .then()
            .statusCode(403);

        verify(userService, never()).createUser(any());
    }

    @Test
    void testCreateUser_MissingEmail_Returns400() {
        asAdmin();

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("firstName", "New", "lastName", "User", "role", "EMPLOYEE", "position", "Engineer", "status", "Active"))
            .when().post("/users")
            .then()
            .statusCode(400);

        verify(userService, never()).createUser(any());
    }

    @Test
    void testCreateUser_MissingFirstName_Returns400() {
        asAdmin();

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("email", "new@example.com", "lastName", "User", "role", "EMPLOYEE", "position", "Engineer", "status", "Active"))
            .when().post("/users")
            .then()
            .statusCode(400);

        verify(userService, never()).createUser(any());
    }

    @Test
    void testCreateUser_MissingRole_Returns400() {
        asAdmin();

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("email", "new@example.com", "firstName", "New", "lastName", "User", "position", "Engineer", "status", "Active"))
            .when().post("/users")
            .then()
            .statusCode(400);

        verify(userService, never()).createUser(any());
    }

    @Test
    void testCreateUser_InvalidRole_Returns400() {
        asAdmin();

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("email", "new@example.com", "firstName", "New", "lastName", "User", "role", "SUPERUSER", "position", "Engineer", "status", "Active"))
            .when().post("/users")
            .then()
            .statusCode(400);

        verify(userService, never()).createUser(any());
    }

    @Test
    void testCreateUser_UsernameExistsException_Returns409WithNeutralMessage() {
        asAdmin();
        when(userService.createUser(org.mockito.ArgumentMatchers.<CreateUserRequest>any()))
                .thenThrow(UsernameExistsException.builder().message("User already exists").build());

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "email", "dup@example.com",
                "firstName", "Dup",
                "lastName", "User",
                "role", "EMPLOYEE",
                "position", "Engineer",
                "status", "Active",
                "seniority", "Engineer",
                "discipline", "Software & DevOps Engineering"
            ))
            .when().post("/users")
            .then()
            .statusCode(409)
            // Must NOT leak the SDK confirmation that the email exists in Cognito.
            .body("message", not(containsString("exists")))
            .body("message", not(containsString("Cognito")))
            .body("message", not(containsString("User already")));
    }

    @Test
    void testCreateUser_GenericException_Returns500WithoutLeakingExceptionMessage() {
        asAdmin();
        // Internal-looking message that must not reach the client.
        when(userService.createUser(org.mockito.ArgumentMatchers.<CreateUserRequest>any()))
                .thenThrow(new RuntimeException("internal DDB throttle on table CatalystFlow_Users"));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "email", "boom@example.com",
                "firstName", "Boom",
                "lastName", "User",
                "role", "EMPLOYEE",
                "position", "Engineer",
                "status", "Active",
                "seniority", "Engineer",
                "discipline", "Software & DevOps Engineering"
            ))
            .when().post("/users")
            .then()
            .statusCode(500)
            .body("message", not(containsString("throttle")))
            .body("message", not(containsString("CatalystFlow_Users")))
            .body("message", not(containsString("RuntimeException")));
    }

    @Test
    void testCreateUser_FutureHireDate_Returns400() {
        asAdmin();

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "email", "new@example.com",
                "firstName", "New",
                "lastName", "User",
                "role", "EMPLOYEE",
                "position", "Engineer",
                "status", "Active",
                "seniority", "Engineer",
                "discipline", "Software & DevOps Engineering",
                "hireDate", "2099-01-01"
            ))
            .when().post("/users")
            .then()
            .statusCode(400);

        verify(userService, never()).createUser(any());
    }

    @Test
    void testCreateUser_MissingSeniority_Returns400() {
        asAdmin();

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "email", "new@example.com",
                "firstName", "New",
                "lastName", "User",
                "role", "EMPLOYEE",
                "position", "Engineer",
                "status", "Active",
                "discipline", "Software & DevOps Engineering"
            ))
            .when().post("/users")
            .then()
            .statusCode(400)
            .body("message", containsString("seniority"));

        verify(userService, never()).createUser(any());
    }

    @Test
    void testCreateUser_MissingDiscipline_Returns400() {
        asAdmin();

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "email", "new@example.com",
                "firstName", "New",
                "lastName", "User",
                "role", "EMPLOYEE",
                "position", "Engineer",
                "status", "Active",
                "seniority", "Engineer"
            ))
            .when().post("/users")
            .then()
            .statusCode(400)
            .body("message", containsString("discipline"));

        verify(userService, never()).createUser(any());
    }

    @Test
    void testCreateUser_BlankSeniority_Returns400() {
        asAdmin();

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "email", "new@example.com",
                "firstName", "New",
                "lastName", "User",
                "role", "EMPLOYEE",
                "position", "Engineer",
                "status", "Active",
                "seniority", "   ",
                "discipline", "Software & DevOps Engineering"
            ))
            .when().post("/users")
            .then()
            .statusCode(400)
            .body("message", containsString("seniority"));

        verify(userService, never()).createUser(any());
    }

    // ── Get user ──────────────────────────────────────────────────────────────
    //
    // Authz: isSelf || isAdmin || isSubjectsManager (mirrors /users/{id}/manager).
    // PII stripping: email, hireDate, seniority are nulled out for non-ADMIN
    // callers and omitted from the wire via Jackson non-null inclusion.

    @Test
    void testGetUser_Admin_Returns200WithPii() {
        asAdmin();
        User user = makeUser("u1", "user@example.com", "Test", "User", "EMPLOYEE");
        user.setReportingTo("manager-id");
        user.setHireDate("2023-06-01");
        user.setSeniority("Engineer");
        when(userService.getUser("u1")).thenReturn(Optional.of(user));

        given()
            .when().get("/users/u1")
            .then()
            .statusCode(200)
            .body("userId", equalTo("u1"))
            .body("email", equalTo("user@example.com"))
            .body("hireDate", equalTo("2023-06-01"))
            .body("seniority", equalTo("Engineer"))
            .body("reportingTo", equalTo("manager-id"));
    }

    @Test
    void testGetUser_Self_Returns200WithFullPii() {
        // Self lookup keeps PII — the user's own data is theirs to see.
        // Review-form depends on this to read `seniority` for the AI suggest
        // request. Strip only applies to cross-user reads.
        asUser(); // caller = "user-id"
        User user = makeUser("user-id", "self@example.com", "Test", "User", "EMPLOYEE");
        user.setHireDate("2023-06-01");
        user.setSeniority("Engineer");
        when(userService.getUser("user-id")).thenReturn(Optional.of(user));

        given()
            .when().get("/users/user-id")
            .then()
            .statusCode(200)
            .body("userId", equalTo("user-id"))
            .body("firstName", equalTo("Test"))
            .body("email", equalTo("self@example.com"))
            .body("hireDate", equalTo("2023-06-01"))
            .body("seniority", equalTo("Engineer"));
    }

    @Test
    void testGetUser_Manager_DirectReport_Returns200WithFullData() {
        // Manager viewing their direct report — authorized lookup, full data
        // returned. Dashboard member-drawer + my-team details render `email`
        // and `hireDate`; manager-review-form reads `seniority` for the AI
        // suggest payload. The 403 gate already blocks unauthorized lookups.
        asManager(); // caller = "mgr-id"
        User employee = makeUser("emp-id", "emp@example.com", "Jane", "Doe", "EMPLOYEE");
        employee.setReportingTo("mgr-id");
        employee.setHireDate("2023-06-01");
        employee.setSeniority("Engineer");
        when(userService.getUser("emp-id")).thenReturn(Optional.of(employee));

        given()
            .when().get("/users/emp-id")
            .then()
            .statusCode(200)
            .body("userId", equalTo("emp-id"))
            .body("firstName", equalTo("Jane"))
            .body("email", equalTo("emp@example.com"))
            .body("hireDate", equalTo("2023-06-01"))
            .body("seniority", equalTo("Engineer"));
    }

    @Test
    void testGetUser_Manager_UnrelatedUser_Returns403() {
        asManager(); // caller = "mgr-id"
        User other = makeUser("other-id", "other@example.com", "Other", "User", "EMPLOYEE");
        other.setReportingTo("different-mgr");
        when(userService.getUser("other-id")).thenReturn(Optional.of(other));

        given()
            .when().get("/users/other-id")
            .then()
            .statusCode(403);
    }

    @Test
    void testGetUser_Employee_OtherUser_Returns403() {
        asUser(); // caller = "user-id", not admin/manager
        User other = makeUser("u1", "user@example.com", "Test", "User", "EMPLOYEE");
        other.setReportingTo("some-mgr");
        when(userService.getUser("u1")).thenReturn(Optional.of(other));

        given()
            .when().get("/users/u1")
            .then()
            .statusCode(403);
    }

    @Test
    void testGetUser_NotFound_Returns404() {
        asUser();
        when(userService.getUser("missing")).thenReturn(Optional.empty());

        given()
            .when().get("/users/missing")
            .then()
            .statusCode(404);
    }

    // ── Update user (ADMIN only) ──────────────────────────────────────────────

    @Test
    void testUpdateUser_Admin_Returns200() {
        asAdmin();
        doNothing().when(userService).updateUser(eq("u1"), any());

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("firstName", "Updated", "lastName", "Name", "role", "ADMIN"))
            .when().put("/users/u1")
            .then()
            .statusCode(200)
            .body("message", equalTo("User updated"));

        verify(userService, times(1)).updateUser(eq("u1"), any());
    }

    @Test
    void testUpdateUser_NonAdmin_Returns403() {
        asUser();

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("firstName", "Updated"))
            .when().put("/users/u1")
            .then()
            .statusCode(403);

        verify(userService, never()).updateUser(any(), any());
    }

    @Test
    void testUpdateUser_NotFound_Returns404() {
        asAdmin();
        doThrow(new NoSuchElementException("User not found: u-missing"))
                .when(userService).updateUser(eq("u-missing"), any());

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("firstName", "Updated"))
            .when().put("/users/u-missing")
            .then()
            .statusCode(404);
    }

    @Test
    void testUpdateUser_BlankSeniority_Returns400() {
        asAdmin();

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("seniority", "   "))
            .when().put("/users/u1")
            .then()
            .statusCode(400)
            .body("message", containsString("seniority"));

        verify(userService, never()).updateUser(any(), any());
    }

    @Test
    void testUpdateUser_BlankDiscipline_Returns400() {
        asAdmin();

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("discipline", ""))
            .when().put("/users/u1")
            .then()
            .statusCode(400)
            .body("message", containsString("discipline"));

        verify(userService, never()).updateUser(any(), any());
    }

    // ── List users — role filter ──────────────────────────────────────────────

    @Test
    void testListUsers_WithValidRoleFilter_Returns200() {
        asAdmin();
        when(userService.listUsersByRole("EMPLOYEE")).thenReturn(List.of(
                makeUser("u1", "emp@example.com", "Jane", "Doe", "EMPLOYEE")
        ));

        given()
            .queryParam("role", "EMPLOYEE")
            .when().get("/users")
            .then()
            .statusCode(200)
            .body("users.size()", equalTo(1));

        verify(userService, times(1)).listUsersByRole("EMPLOYEE");
        verify(userService, never()).listUsers();
    }

    @Test
    void testListUsers_WithInvalidRoleFilter_Returns400() {
        asAdmin();

        given()
            .queryParam("role", "SUPERUSER")
            .when().get("/users")
            .then()
            .statusCode(400);

        verify(userService, never()).listUsers();
        verify(userService, never()).listUsersByRole(any());
    }

    // ── Get user manager ──────────────────────────────────────────────────────

    @Test
    void testGetUserManager_Admin_AnyUser_Returns200() {
        asAdmin();
        User employee = makeUser("emp-id", "emp@example.com", "Jane", "Doe", "EMPLOYEE");
        employee.setReportingTo("mgr-id");
        User manager = makeUser("mgr-id", "mgr@example.com", "John", "Smith", "MANAGER");

        when(userService.getUser("emp-id")).thenReturn(Optional.of(employee));
        when(userService.getUser("mgr-id")).thenReturn(Optional.of(manager));

        given()
            .when().get("/users/emp-id/manager")
            .then()
            .statusCode(200)
            .body("userId", equalTo("mgr-id"));
    }

    @Test
    void testGetUserManager_Employee_OwnManager_Returns200() {
        when(claimsContext.isAdmin()).thenReturn(false);
        when(claimsContext.isManager()).thenReturn(false);
        when(claimsContext.getUserId()).thenReturn("emp-id");

        User employee = makeUser("emp-id", "emp@example.com", "Jane", "Doe", "EMPLOYEE");
        employee.setReportingTo("mgr-id");
        User manager = makeUser("mgr-id", "mgr@example.com", "John", "Smith", "MANAGER");

        when(userService.getUser("emp-id")).thenReturn(Optional.of(employee));
        when(userService.getUser("mgr-id")).thenReturn(Optional.of(manager));

        given()
            .when().get("/users/emp-id/manager")
            .then()
            .statusCode(200)
            .body("userId", equalTo("mgr-id"));
    }

    @Test
    void testGetUserManager_Employee_OtherUser_Returns403() {
        when(claimsContext.isAdmin()).thenReturn(false);
        when(claimsContext.isManager()).thenReturn(false);
        when(claimsContext.getUserId()).thenReturn("emp-id");

        User otherUser = makeUser("other-id", "other@example.com", "Other", "User", "EMPLOYEE");
        otherUser.setReportingTo("some-mgr");
        when(userService.getUser("other-id")).thenReturn(Optional.of(otherUser));

        given()
            .when().get("/users/other-id/manager")
            .then()
            .statusCode(403);
    }

    @Test
    void testGetUserManager_Manager_DirectReport_Returns200() {
        asManager();

        User employee = makeUser("emp-id", "emp@example.com", "Jane", "Doe", "EMPLOYEE");
        employee.setReportingTo("mgr-id"); // reports to the caller
        User manager = makeUser("mgr-id", "mgr@example.com", "John", "Smith", "MANAGER");

        when(userService.getUser("emp-id")).thenReturn(Optional.of(employee));
        when(userService.getUser("mgr-id")).thenReturn(Optional.of(manager));

        given()
            .when().get("/users/emp-id/manager")
            .then()
            .statusCode(200);
    }

    @Test
    void testGetUserManager_Manager_UnrelatedUser_Returns403() {
        asManager();

        User otherUser = makeUser("other-id", "other@example.com", "Other", "User", "EMPLOYEE");
        otherUser.setReportingTo("different-mgr"); // reports to someone else
        when(userService.getUser("other-id")).thenReturn(Optional.of(otherUser));

        given()
            .when().get("/users/other-id/manager")
            .then()
            .statusCode(403);
    }

    @Test
    void testGetUserManager_UserNotFound_Returns404() {
        asAdmin();
        when(userService.getUser("missing")).thenReturn(Optional.empty());

        given()
            .when().get("/users/missing/manager")
            .then()
            .statusCode(404);
    }

    @Test
    void testGetUserManager_UserHasNoManager_Returns404() {
        asAdmin();
        User userWithNoManager = makeUser("emp-id", "emp@example.com", "Jane", "Doe", "EMPLOYEE");
        // reportingTo is null — no manager assigned
        when(userService.getUser("emp-id")).thenReturn(Optional.of(userWithNoManager));

        given()
            .when().get("/users/emp-id/manager")
            .then()
            .statusCode(404);
    }

    @Test
    void testGetUserManager_ManagerNotFoundInDb_Returns404() {
        asAdmin();
        User employee = makeUser("emp-id", "emp@example.com", "Jane", "Doe", "EMPLOYEE");
        employee.setReportingTo("deleted-mgr");
        when(userService.getUser("emp-id")).thenReturn(Optional.of(employee));
        when(userService.getUser("deleted-mgr")).thenReturn(Optional.empty());

        given()
            .when().get("/users/emp-id/manager")
            .then()
            .statusCode(404);
    }

    // ── Get manager team ──────────────────────────────────────────────────────

    @Test
    void testGetManagerTeam_Admin_Returns200WithTeam() {
        asAdmin();
        when(userService.getTeam("mgr-id")).thenReturn(List.of(
                makeUser("emp-id", "emp@example.com", "Jane", "Doe", "EMPLOYEE")
        ));

        given()
            .when().get("/users/mgr-id/team")
            .then()
            .statusCode(200)
            .body("users.size()", equalTo(1))
            .body("count", equalTo(1));
    }

    @Test
    void testGetManagerTeam_Admin_EmptyTeam_Returns200() {
        asAdmin();
        when(userService.getTeam("mgr-id")).thenReturn(List.of());

        given()
            .when().get("/users/mgr-id/team")
            .then()
            .statusCode(200)
            .body("users.size()", equalTo(0))
            .body("count", equalTo(0));
    }

    @Test
    void testGetManagerTeam_Manager_OwnTeam_Returns200() {
        asManager();
        when(userService.getTeam("mgr-id")).thenReturn(List.of(
                makeUser("emp-id", "emp@example.com", "Jane", "Doe", "EMPLOYEE")
        ));

        given()
            .when().get("/users/mgr-id/team")
            .then()
            .statusCode(200)
            .body("users.size()", equalTo(1));
    }

    @Test
    void testGetManagerTeam_Manager_OtherTeam_Returns403() {
        asManager(); // caller is mgr-id

        given()
            .when().get("/users/other-mgr/team")
            .then()
            .statusCode(403);

        verify(userService, never()).getTeam(any());
    }

    @Test
    void testGetManagerTeam_Employee_Returns403() {
        asUser();

        given()
            .when().get("/users/mgr-id/team")
            .then()
            .statusCode(403);

        verify(userService, never()).getTeam(any());
    }

    @Test
    void testGetManagerTeam_ServiceThrowsException_Returns500() {
        asAdmin();
        when(userService.getTeam(any())).thenThrow(new RuntimeException("DynamoDB unavailable"));

        given()
            .when().get("/users/mgr-id/team")
            .then()
            .statusCode(500);
    }

    // ── CORS ──────────────────────────────────────────────────────────────────

    @Test
    void testAllResponses_HaveCorsHeader() {
        given()
            .when().get("/users/health")
            .then()
            .header("Access-Control-Allow-Origin", "*");
    }

    // ── validateHireDate (Sydney-zone regression guard) ───────────────────────
    //
    // Lambda runs UTC. Before this fix the validator compared the incoming
    // hireDate against `LocalDate.now()` — so a Sydney early-morning
    // "hireDate=today" was rejected as future. Helper now takes an explicit
    // `today` so we test the comparison logic deterministically; the resource
    // layer passes `LocalDate.now(AU_ZONE)` at the call site.

    @Test
    void validateHireDate_NullIsAccepted() {
        assertNull(UserResource.validateHireDate(null, LocalDate.of(2026, 5, 19)));
    }

    @Test
    void validateHireDate_BlankIsAccepted() {
        assertNull(UserResource.validateHireDate("", LocalDate.of(2026, 5, 19)));
        assertNull(UserResource.validateHireDate("   ", LocalDate.of(2026, 5, 19)));
    }

    @Test
    void validateHireDate_TodayIsAccepted() {
        // Regression guard: previously a UTC clock would have today = D-1 during
        // Sydney's 00:00–10:00 AM window, rejecting hireDate=D as "future".
        LocalDate today = LocalDate.of(2026, 5, 19);
        assertNull(UserResource.validateHireDate("2026-05-19", today));
    }

    @Test
    void validateHireDate_PastIsAccepted() {
        assertNull(UserResource.validateHireDate("2020-01-01", LocalDate.of(2026, 5, 19)));
    }

    @Test
    void validateHireDate_OneDayFutureRejected() {
        assertEquals(
                "'hireDate' cannot be a future date",
                UserResource.validateHireDate("2026-05-20", LocalDate.of(2026, 5, 19)));
    }

    @Test
    void validateHireDate_InvalidFormatRejected() {
        assertEquals(
                "'hireDate' must be a valid date in format YYYY-MM-DD",
                UserResource.validateHireDate("not-a-date", LocalDate.of(2026, 5, 19)));
    }

    @Test
    void validateHireDate_AuZoneConstantPointsToSydney() {
        // Pins the timezone choice so a refactor doesn't silently re-introduce UTC.
        assertEquals("Australia/Sydney", UserResource.AU_ZONE.getId());
    }
}

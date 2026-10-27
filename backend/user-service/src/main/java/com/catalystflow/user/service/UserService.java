package com.catalystflow.user.service;

import com.catalystflow.user.model.CreateUserRequest;
import com.catalystflow.user.model.UpdateUserRequest;
import com.catalystflow.user.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Manages users across two systems:
 *  - Cognito User Pool  — source of truth for authentication
 *  - DynamoDB UsersTable — stores enriched profile data (name, role, reportingTo)
 *
 * On create: Cognito AdminCreateUser → get sub → DynamoDB PutItem
 * On update: DynamoDB GetItem (for email/current role) → Cognito updates → DynamoDB UpdateItem
 */
@ApplicationScoped
public class UserService {

    private static final Logger LOG = Logger.getLogger(UserService.class);

    @Inject
    DynamoDbClient dynamoDb;

    /**
     * Used by {@link #assignTeamMembers(String, List)} for parallel fan-out writes.
     * Single-row sync reads/writes still go through {@link #dynamoDb}.
     */
    @Inject
    DynamoDbAsyncClient dynamoDbAsync;

    @Inject
    CognitoIdentityProviderClient cognitoClient;

    @ConfigProperty(name = "users.table-name")
    String usersTableName;

    @ConfigProperty(name = "cognito.user-pool-id")
    String userPoolId;

    public List<User> listUsers() {
        LOG.infof("Scanning users table: %s", usersTableName);
        return scanAllPages(ScanRequest.builder().tableName(usersTableName));
    }

    public List<User> listUsersByRole(String role) {
        LOG.infof("Scanning users table: %s for role: %s", usersTableName, role);
        return scanAllPages(ScanRequest.builder()
                .tableName(usersTableName)
                .filterExpression("#r = :role")
                .expressionAttributeNames(Map.of("#r", "role"))
                .expressionAttributeValues(Map.of(":role", AttributeValue.builder().s(role).build())));
    }

    /**
     * Run a paginated DDB scan and return every matching row as User objects.
     * DynamoDB caps each scan response at 1MB; without `LastEvaluatedKey`
     * pagination, callers see only the first page and silently miss data.
     */
    private List<User> scanAllPages(ScanRequest.Builder requestBuilder) {
        List<User> all = new ArrayList<>();
        Map<String, AttributeValue> lastEvaluatedKey = null;
        do {
            ScanRequest req = (lastEvaluatedKey == null)
                    ? requestBuilder.build()
                    : requestBuilder.exclusiveStartKey(lastEvaluatedKey).build();
            ScanResponse response = dynamoDb.scan(req);
            response.items().stream().map(this::toUser).forEach(all::add);
            lastEvaluatedKey = response.hasLastEvaluatedKey() ? response.lastEvaluatedKey() : null;
        } while (lastEvaluatedKey != null);
        return all;
    }

    public Optional<User> getUser(String userId) {
        LOG.infof("Getting user: %s", userId);
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(usersTableName)
                .key(Map.of("userId", AttributeValue.builder().s(userId).build()))
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toUser(response.item()));
    }

    public User createUser(CreateUserRequest request) {
        LOG.infof("Creating user in Cognito: email=%s role=%s", request.getEmail(), request.getRole());

        String fullName = request.getFirstName() + " " + request.getLastName();

        // 1. Create user in Cognito — Cognito will send a temporary password to the user's email.
        //    If this step fails (e.g. UsernameExistsException) the exception propagates as-is;
        //    no rollback is needed because no Cognito user was created.
        AdminCreateUserRequest cognitoRequest = AdminCreateUserRequest.builder()
                .userPoolId(userPoolId)
                .username(request.getEmail())
                .messageAction("false".equalsIgnoreCase(System.getenv("USER_INVITATIONS_ENABLED")) ? MessageActionType.SUPPRESS : null)
                .userAttributes(
                        AttributeType.builder().name("email").value(request.getEmail()).build(),
                        AttributeType.builder().name("name").value(fullName).build(),
                        AttributeType.builder().name("email_verified").value("true").build()
                )
                .build();

        AdminCreateUserResponse cognitoResponse = cognitoClient.adminCreateUser(cognitoRequest);

        // From this point on the Cognito user exists. Any downstream failure must trigger a
        // compensating AdminDeleteUser so we don't leave an orphan that collides on retry.
        try {
            // 2. Extract the Cognito sub (unique user ID)
            String userId = cognitoResponse.user().attributes().stream()
                    .filter(a -> "sub".equals(a.name()))
                    .findFirst()
                    .map(AttributeType::value)
                    .orElseThrow(() -> new IllegalStateException(
                            "Cognito did not return a sub for the new user"));

            // 3. Add user to the appropriate Cognito group (ADMIN, MANAGER, or EMPLOYEE)
            String groupName = resolveGroup(request.getRole());
            cognitoClient.adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                    .userPoolId(userPoolId)
                    .username(request.getEmail())
                    .groupName(groupName)
                    .build());

            // 4. Save enriched profile to DynamoDB
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("userId", AttributeValue.builder().s(userId).build());
            item.put("email", AttributeValue.builder().s(request.getEmail()).build());
            item.put("firstName", AttributeValue.builder().s(request.getFirstName()).build());
            item.put("lastName", AttributeValue.builder().s(request.getLastName()).build());
            item.put("role", AttributeValue.builder().s(groupName).build());
            item.put("position", AttributeValue.builder().s(request.getPosition()).build());
            item.put("status", AttributeValue.builder().s(request.getStatus()).build());
            if (request.getReportingTo() != null && !request.getReportingTo().isBlank()) {
                item.put("reportingTo", AttributeValue.builder().s(request.getReportingTo()).build());
            }
            if (request.getHireDate() != null && !request.getHireDate().isBlank()) {
                item.put("hireDate", AttributeValue.builder().s(request.getHireDate()).build());
            }
            putOptionalString(item, "seniority", request.getSeniority());
            putOptionalString(item, "discipline", request.getDiscipline());
            String createdAt = Instant.now().toString();
            item.put("updatedAt", AttributeValue.builder().s(createdAt).build());

            dynamoDb.putItem(PutItemRequest.builder().tableName(usersTableName).item(item).build());
            LOG.infof("User created: userId=%s email=%s role=%s", userId, request.getEmail(), groupName);

            // 5. Assign team members if provided (MANAGER or ADMIN only)
            if (request.getTeamMembers() != null && !request.getTeamMembers().isEmpty()) {
                assignTeamMembers(userId, request.getTeamMembers());
            }

            return new User()
                    .userId(userId)
                    .email(request.getEmail())
                    .firstName(request.getFirstName())
                    .lastName(request.getLastName())
                    .role(groupName)
                    .position(request.getPosition())
                    .status(request.getStatus())
                    .reportingTo(request.getReportingTo())
                    .hireDate(request.getHireDate())
                    .seniority(request.getSeniority())
                    .discipline(request.getDiscipline())
                    .updatedAt(createdAt);
        } catch (RuntimeException downstream) {
            // Compensating rollback. We do NOT swallow the original exception — caller
            // still needs to know the create failed.
            String correlationId = UUID.randomUUID().toString();
            LOG.errorf(downstream,
                    "createUser failed after Cognito create succeeded; rolling back. email=%s correlationId=%s",
                    request.getEmail(), correlationId);
            rollbackCognitoUser(request.getEmail(), correlationId);
            throw downstream;
        }
    }

    /**
     * Best-effort compensating delete after a partial createUser failure.
     * If the delete itself fails, the Cognito user is orphaned — log enough
     * detail (correlation id + email) for an admin to clean up manually.
     */
    private void rollbackCognitoUser(String email, String correlationId) {
        try {
            cognitoClient.adminDeleteUser(AdminDeleteUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .build());
            LOG.infof("Rolled back Cognito user after createUser failure: email=%s correlationId=%s",
                    email, correlationId);
        } catch (RuntimeException rollbackError) {
            LOG.errorf(rollbackError,
                    "ORPHAN Cognito user — manual cleanup required. email=%s correlationId=%s",
                    email, correlationId);
        }
    }

    public void updateUser(String userId, UpdateUserRequest request) {
        // 1. Read current user from DynamoDB to get email and current role
        Optional<User> existing = getUser(userId);
        if (existing.isEmpty()) {
            throw new NoSuchElementException("User not found: " + userId);
        }
        User current = existing.get();

        // 2. Update Cognito user attributes (name) if firstName or lastName changed
        if ((request.getFirstName() != null && !request.getFirstName().isBlank()) ||
            (request.getLastName() != null && !request.getLastName().isBlank())) {
            String newFirst = request.getFirstName() != null ? request.getFirstName() : current.getFirstName();
            String newLast = request.getLastName() != null ? request.getLastName() : current.getLastName();
            cognitoClient.adminUpdateUserAttributes(AdminUpdateUserAttributesRequest.builder()
                    .userPoolId(userPoolId)
                    .username(current.getEmail())
                    .userAttributes(
                            AttributeType.builder().name("name").value(newFirst + " " + newLast).build()
                    )
                    .build());
        }

        // 3. Update Cognito group if role changed
        if (request.getRole() != null && !request.getRole().equalsIgnoreCase(current.getRole())) {
            String newGroup = resolveGroup(request.getRole());
            String oldGroup = resolveGroup(current.getRole());

            cognitoClient.adminRemoveUserFromGroup(AdminRemoveUserFromGroupRequest.builder()
                    .userPoolId(userPoolId)
                    .username(current.getEmail())
                    .groupName(oldGroup)
                    .build());

            cognitoClient.adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                    .userPoolId(userPoolId)
                    .username(current.getEmail())
                    .groupName(newGroup)
                    .build());
        }

        // 4. Build DynamoDB update expression dynamically
        Map<String, AttributeValue> expressionValues = new HashMap<>();
        Map<String, String> expressionNames = new HashMap<>();
        StringBuilder updateExpr = new StringBuilder("SET");
        boolean first = true;

        if (request.getFirstName() != null && !request.getFirstName().isBlank()) {
            updateExpr.append(first ? " " : ", ").append("firstName = :firstName");
            expressionValues.put(":firstName", AttributeValue.builder().s(request.getFirstName()).build());
            first = false;
        }
        if (request.getLastName() != null && !request.getLastName().isBlank()) {
            updateExpr.append(first ? " " : ", ").append("lastName = :lastName");
            expressionValues.put(":lastName", AttributeValue.builder().s(request.getLastName()).build());
            first = false;
        }
        if (request.getRole() != null && !request.getRole().isBlank()) {
            updateExpr.append(first ? " " : ", ").append("#r = :role");
            expressionNames.put("#r", "role");
            expressionValues.put(":role", AttributeValue.builder().s(resolveGroup(request.getRole())).build());
            first = false;
        }
        if (request.getPosition() != null && !request.getPosition().isBlank()) {
            updateExpr.append(first ? " " : ", ").append("#p = :position");
            expressionNames.put("#p", "position");
            expressionValues.put(":position", AttributeValue.builder().s(request.getPosition()).build());
            first = false;
        }
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            updateExpr.append(first ? " " : ", ").append("#s = :status");
            expressionNames.put("#s", "status");
            expressionValues.put(":status", AttributeValue.builder().s(request.getStatus()).build());
            first = false;
        }
        if (request.getReportingTo() != null) {
            updateExpr.append(first ? " " : ", ").append("reportingTo = :reportingTo");
            expressionValues.put(":reportingTo", AttributeValue.builder().s(request.getReportingTo()).build());
            first = false;
        }
        if (request.getHireDate() != null && !request.getHireDate().isBlank()) {
            updateExpr.append(first ? " " : ", ").append("hireDate = :hireDate");
            expressionValues.put(":hireDate", AttributeValue.builder().s(request.getHireDate()).build());
            first = false;
        }
        if (request.getSeniority() != null) {
            updateExpr.append(first ? " " : ", ").append("seniority = :seniority");
            expressionValues.put(":seniority", AttributeValue.builder().s(request.getSeniority()).build());
            first = false;
        }
        if (request.getDiscipline() != null) {
            updateExpr.append(first ? " " : ", ").append("discipline = :discipline");
            expressionValues.put(":discipline", AttributeValue.builder().s(request.getDiscipline()).build());
            first = false;
        }

        // Always bump the last-modified timestamp on any update — even a
        // role-only change must register. This is what powers the
        // "Last Modified" column in Role Management. Because it always adds a
        // value, the updateItem below always runs.
        updateExpr.append(first ? " " : ", ").append("updatedAt = :updatedAt");
        expressionValues.put(":updatedAt", AttributeValue.builder().s(Instant.now().toString()).build());

        if (!expressionValues.isEmpty()) {
            UpdateItemRequest.Builder builder = UpdateItemRequest.builder()
                    .tableName(usersTableName)
                    .key(Map.of("userId", AttributeValue.builder().s(userId).build()))
                    .updateExpression(updateExpr.toString())
                    .expressionAttributeValues(expressionValues);

            if (!expressionNames.isEmpty()) {
                builder.expressionAttributeNames(expressionNames);
            }

            dynamoDb.updateItem(builder.build());
        }

        // 5. Assign team members if provided (MANAGER or ADMIN only)
        if (request.getTeamMembers() != null && !request.getTeamMembers().isEmpty()) {
            assignTeamMembers(userId, request.getTeamMembers());
        }

        LOG.infof("Updated user: %s", userId);
    }

    private User toUser(Map<String, AttributeValue> item) {
        return new User()
                .userId(attrStr(item, "userId"))
                .email(attrStr(item, "email"))
                .firstName(attrStr(item, "firstName"))
                .lastName(attrStr(item, "lastName"))
                .role(attrStr(item, "role"))
                .position(attrStr(item, "position"))
                .status(attrStr(item, "status"))
                .reportingTo(attrStr(item, "reportingTo"))
                .hireDate(attrStr(item, "hireDate"))
                .seniority(attrStr(item, "seniority"))
                .discipline(attrStr(item, "discipline"))
                .updatedAt(attrStr(item, "updatedAt"));
    }

    private String attrStr(Map<String, AttributeValue> item, String key) {
        AttributeValue val = item.get(key);
        return val != null ? val.s() : null;
    }

    private void putOptionalString(Map<String, AttributeValue> item, String key, String value) {
        if (value != null && !value.isBlank()) {
            item.put(key, AttributeValue.builder().s(value).build());
        }
    }

    /**
     * Returns all users whose reportingTo field matches the given managerId.
     */
    public List<User> getTeam(String managerId) {
        LOG.infof("Scanning for direct reports of manager: %s", managerId);
        return scanAllPages(ScanRequest.builder()
                .tableName(usersTableName)
                .filterExpression("reportingTo = :managerId")
                .expressionAttributeValues(Map.of(
                        ":managerId", AttributeValue.builder().s(managerId).build()
                )));
    }

    /**
     * Updates the reportingTo field for each member in the list to point to managerId.
     * Called when creating or updating a MANAGER or ADMIN with a teamMembers list.
     *
     * <p>Fans out via {@link DynamoDbAsyncClient} so the wall time is the slowest
     * single round-trip rather than the sum of N sequential round-trips. Lambda
     * 29 s timeout is therefore no longer a hard cap on team size.
     *
     * <p>Partial-failure semantics: every memberId is attempted. If any updateItem
     * fails, the others still run to completion; the method then throws 500 listing
     * the memberIds that failed so the caller can retry just those.
     */
    public void assignTeamMembers(String managerId, List<String> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) return;

        List<CompletableFuture<String>> futures = memberIds.stream()
                .map(memberId -> dynamoDbAsync.updateItem(UpdateItemRequest.builder()
                                .tableName(usersTableName)
                                .key(Map.of("userId", AttributeValue.builder().s(memberId).build()))
                                .updateExpression("SET reportingTo = :managerId")
                                .expressionAttributeValues(Map.of(
                                        ":managerId", AttributeValue.builder().s(managerId).build()))
                                .build())
                        // .handle() runs on both success and failure so one bad call
                        // doesn't short-circuit allOf — we want every call attempted.
                        // null = success, non-null memberId = failed.
                        .handle((resp, err) -> {
                            if (err != null) {
                                LOG.warnf(err, "Failed to assign member %s to manager %s",
                                        memberId, managerId);
                                return memberId;
                            }
                            LOG.infof("Assigned member: %s to manager: %s", memberId, managerId);
                            return null;
                        }))
                .toList();

        List<String> failedMemberIds = CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .filter(Objects::nonNull)
                        .toList())
                .join();

        if (!failedMemberIds.isEmpty()) {
            LOG.errorf("assignTeamMembers partial failure: managerId=%s failed=%s",
                    managerId, failedMemberIds);
            throw new WebApplicationException(
                    "Failed to assign " + failedMemberIds.size()
                            + " team member(s): " + failedMemberIds,
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    /** Maps role string to Cognito group name. */
    private String resolveGroup(String role) {
        if (role == null) return "EMPLOYEE";
        return switch (role.toUpperCase()) {
            case "ADMIN" -> "ADMIN";
            case "MANAGER" -> "MANAGER";
            default -> "EMPLOYEE";
        };
    }
}

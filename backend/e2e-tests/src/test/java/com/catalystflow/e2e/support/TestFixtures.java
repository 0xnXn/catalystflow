package com.catalystflow.e2e.support;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Manages the lifecycle of E2E test users.
 *
 * Bootstrap sequence (runs once per test run):
 *  1. Create admin directly in Cognito + write their DynamoDB record.
 *  2. Authenticate admin to obtain a token.
 *  3. Create manager via POST /users (API creates Cognito + DynamoDB records).
 *  4. Set manager's permanent password + authenticate.
 *  5. Create employee via POST /users, reporting to manager.
 *  6. Set employee's permanent password + authenticate.
 *
 * Teardown sequence (runs once at the end of the test run):
 *  1. Delete DynamoDB records for all test users.
 *  2. Delete Cognito accounts for all test users.
 *
 * All three personas (admin/manager/employee) are available as public fields.
 * A dedicated CRUD subject user is also created for UserLifecycleE2ETest
 * to avoid modifying the shared persona users.
 */
public class TestFixtures {

    private static final Logger LOG = LoggerFactory.getLogger(TestFixtures.class);

    /** Immutable snapshot of a test user identity. */
    public record TestUser(String userId, String email, String token) {}

    // ── Shared test personas ──────────────────────────────────────────────────
    public TestUser admin;
    public TestUser manager;
    public TestUser employee;

    // ── Cleanup tracking ─────────────────────────────────────────────────────
    private final List<String> cognitoEmailsToDelete   = new ArrayList<>();
    private final List<String> dynamoUserIdsToDelete   = new ArrayList<>();
    private final List<String> templateIdsToDelete     = new ArrayList<>();
    private final List<String> cycleIdsToDelete        = new ArrayList<>();
    private final List<String> reviewIdsToDelete       = new ArrayList<>();

    private CognitoAuthManager cognito;
    private DynamoDbClient     dynamo;

    // ── Well-known test password (satisfies default Cognito complexity policy) ─
    // Pattern: uppercase + lowercase + digit + special, length > 12.
    private String testPassword() {
        return "E2eTest@1Aa!" + E2EConfig.RUN_ID;
    }

    // ─────────────────────────────────────────────────────────────────────────

    public void setup() {
        RestAssured.baseURI = E2EConfig.API_URL;

        cognito = new CognitoAuthManager(E2EConfig.USER_POOL_ID, E2EConfig.CLIENT_ID, E2EConfig.REGION);
        dynamo  = DynamoDbClient.builder()
                .region(Region.of(E2EConfig.REGION))
                .httpClientBuilder(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.builder())
                .build();

        String runId = E2EConfig.RUN_ID;

        // ── 1. Bootstrap admin ────────────────────────────────────────────────
        String adminEmail = "e2e-admin-" + runId + "@catalystflow-test.invalid";
        String adminId    = cognito.createUser(adminEmail, "EndToEnd", "Admin", "ADMIN", testPassword());
        // Write a DynamoDB record so GET /users/{adminId} works in RBAC tests
        putDynamoRecord(adminId, adminEmail, "EndToEnd", "Admin", "ADMIN", "Administrator", null);
        cognitoEmailsToDelete.add(adminEmail);
        dynamoUserIdsToDelete.add(adminId);

        String adminToken = cognito.authenticate(adminEmail, testPassword());
        admin = new TestUser(adminId, adminEmail, adminToken);

        // ── 2. Create manager via API (Cognito + DynamoDB created by service) ─
        String managerEmail = "e2e-mgr-" + runId + "@catalystflow-test.invalid";
        String managerId    = createUserViaApi(adminToken, managerEmail, "EndToEnd", "Manager",
                "MANAGER", "Team Lead", adminId);
        cognito.setPermanentPassword(managerEmail, testPassword());
        String managerToken = cognito.authenticate(managerEmail, testPassword());
        manager = new TestUser(managerId, managerEmail, managerToken);
        cognitoEmailsToDelete.add(managerEmail);
        dynamoUserIdsToDelete.add(managerId);

        // ── 3. Create employee via API, reporting to manager ──────────────────
        String employeeEmail = "e2e-emp-" + runId + "@catalystflow-test.invalid";
        String employeeId    = createUserViaApi(adminToken, employeeEmail, "EndToEnd", "Employee",
                "EMPLOYEE", "Engineer", managerId);
        cognito.setPermanentPassword(employeeEmail, testPassword());
        String employeeToken = cognito.authenticate(employeeEmail, testPassword());
        employee = new TestUser(employeeId, employeeEmail, employeeToken);
        cognitoEmailsToDelete.add(employeeEmail);
        dynamoUserIdsToDelete.add(employeeId);
    }

    public void teardown() {
        // Order matters: reviews → cycles → templates → users.
        // A cycle cannot be deleted while ACTIVE/SCHEDULED so we go DDB-direct
        // for the lifecycle entities; this avoids state-machine fighting.
        int leaks = 0;
        for (String reviewId : reviewIdsToDelete) {
            if (!deleteFromTable(E2EConfig.REVIEWS_TABLE, "reviewId", reviewId)) leaks++;
        }
        for (String cycleId : cycleIdsToDelete) {
            if (!deleteFromTable(E2EConfig.REVIEW_CYCLES_TABLE, "cycleId", cycleId)) leaks++;
        }
        for (String templateId : templateIdsToDelete) {
            if (!deleteFromTable(E2EConfig.REVIEW_TEMPLATES_TABLE, "templateId", templateId)) leaks++;
        }
        for (String userId : dynamoUserIdsToDelete) {
            if (!deleteFromTable(E2EConfig.USERS_TABLE, "userId", userId)) leaks++;
        }
        for (String email : cognitoEmailsToDelete) {
            try {
                cognito.deleteUser(email);
            } catch (Exception e) {
                // A user that was never fully created (e.g. setup aborted mid-bootstrap)
                // is normal; an AccessDenied here means the role is missing
                // cognito-idp:AdminDeleteUser. Log either way so we can tell them apart.
                LOG.warn("E2E teardown: Cognito deleteUser failed for {} ({}): {}",
                        email, e.getClass().getSimpleName(), e.getMessage());
                leaks++;
            }
        }
        if (leaks > 0) {
            LOG.warn("E2E teardown finished with {} resources NOT cleaned up. " +
                    "Check IAM permissions (dynamodb:DeleteItem on each table, " +
                    "cognito-idp:AdminDeleteUser on the pool) or run the broom script.", leaks);
        }

        if (cognito != null) cognito.close();
        if (dynamo  != null) dynamo.close();
        if (leaks > 0) throw new IllegalStateException("E2E fixture cleanup failed for " + leaks + " resources");
    }

    /** @return true on success, false (and logs) if the delete failed. */
    private boolean deleteFromTable(String tableName, String pkName, String pkValue) {
        try {
            dynamo.deleteItem(DeleteItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(pkName, AttributeValue.builder().s(pkValue).build()))
                    .build());
            return true;
        } catch (Exception e) {
            LOG.warn("E2E teardown: DDB delete failed on {} {}={} ({}): {}",
                    tableName, pkName, pkValue, e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    /**
     * Registers an extra user (created during a test) for full cleanup at teardown.
     * Call this whenever a test creates a user via POST /users or Cognito directly.
     */
    public void registerForCleanup(String userId, String email) {
        if (userId != null) dynamoUserIdsToDelete.add(userId);
        if (email != null) cognitoEmailsToDelete.add(email);
    }

    /** Register a template ID for direct DDB cleanup at the end of the run. */
    public void registerTemplateForCleanup(String templateId) {
        if (templateId != null && !templateId.isBlank()) templateIdsToDelete.add(templateId);
    }

    /** Register a cycle ID for direct DDB cleanup at the end of the run. */
    public void registerCycleForCleanup(String cycleId) {
        if (cycleId != null && !cycleId.isBlank()) cycleIdsToDelete.add(cycleId);
    }

    /** Register a review ID for direct DDB cleanup at the end of the run. */
    public void registerReviewForCleanup(String reviewId) {
        if (reviewId != null && !reviewId.isBlank()) reviewIdsToDelete.add(reviewId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Calls POST /users with admin token and returns the userId from the response.
     * The service creates both the Cognito account and the DynamoDB record.
     */
    private String createUserViaApi(String adminToken,
                                    String email,
                                    String firstName,
                                    String lastName,
                                    String role,
                                    String position,
                                    String reportingTo) {
        Map<String, Object> body = new HashMap<>();
        body.put("email",       email);
        body.put("firstName",   firstName);
        body.put("lastName",    lastName);
        body.put("role",        role);
        body.put("position",    position);
        body.put("status",      "Active");
        body.put("seniority",   "Engineer");
        body.put("discipline",  "Software & DevOps Engineering");
        if (reportingTo != null) body.put("reportingTo", reportingTo);

        return given()
                .header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/users")
                .then()
                .statusCode(201)
                .extract().path("userId");
    }

    /**
     * Writes a user record directly to DynamoDB — used only for the bootstrap admin
     * whose record cannot be created via the API (chicken-and-egg problem).
     */
    private void putDynamoRecord(String userId,
                                 String email,
                                 String firstName,
                                 String lastName,
                                 String role,
                                 String position,
                                 String reportingTo) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("userId",     attr(userId));
        item.put("email",      attr(email));
        item.put("firstName",  attr(firstName));
        item.put("lastName",   attr(lastName));
        item.put("role",       attr(role));
        item.put("position",   attr(position));
        item.put("status",     attr("Active"));
        item.put("seniority",  attr("Engineer"));
        item.put("discipline", attr("Software & DevOps Engineering"));
        if (reportingTo != null) item.put("reportingTo", attr(reportingTo));

        dynamo.putItem(PutItemRequest.builder()
                .tableName(E2EConfig.USERS_TABLE)
                .item(item)
                .build());
    }

    private AttributeValue attr(String value) {
        return AttributeValue.builder().s(value).build();
    }
}

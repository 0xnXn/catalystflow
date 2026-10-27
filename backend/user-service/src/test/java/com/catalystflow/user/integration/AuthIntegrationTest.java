package com.catalystflow.user.integration;

import com.catalystflow.user.model.User;
import com.catalystflow.user.service.UserService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Integration tests for authentication and authorisation enforcement.
 *
 * What makes this different from UserResourceTest (unit tests):
 *
 *   UserResourceTest  — mocks ClaimsContext AND UserService.
 *                       Tests resource logic in total isolation.
 *
 *   AuthIntegrationTest — mocks ONLY UserService.
 *                         ClaimsContext is the REAL bean, populated by AuthFilter
 *                         from the crafted JWT in the Authorization header.
 *                         Tests the full auth pipeline end-to-end:
 *                         JWT → AuthFilter → ClaimsContext → guard → response
 *
 * No real Cognito or DynamoDB needed:
 *   - JWTs are crafted by TestJwtHelper (ClaimsContext never verifies signatures)
 *   - UserService is mocked so no DynamoDB calls occur
 */
@QuarkusTest
@DisplayName("Auth Integration Tests")
class AuthIntegrationTest {

    // Fixed user IDs used across all tests
    static final String ADMIN_ID    = "test-admin-id";
    static final String MANAGER_ID  = "test-manager-id";
    static final String EMPLOYEE_ID = "test-employee-id";
    static final String OTHER_ID    = "other-user-id";

    // ── ClaimsContext is intentionally NOT listed here ────────────────────────
    // The real ClaimsContext runs and gets populated by AuthFilter reading the
    // Authorization header. This is what validates our auth enforcement logic.
    // ─────────────────────────────────────────────────────────────────────────

    @InjectMock
    UserService userService;

    @BeforeEach
    void setUp() {
        reset(userService);

        // Default stubs — used when auth passes and the service layer is reached
        when(userService.listUsers()).thenReturn(List.of(
                sampleUser(EMPLOYEE_ID, "EMPLOYEE")));
        when(userService.getUser(ADMIN_ID))
                .thenReturn(Optional.of(sampleUser(ADMIN_ID, "ADMIN")));
        when(userService.getUser(MANAGER_ID))
                .thenReturn(Optional.of(managerUser()));
        when(userService.getUser(EMPLOYEE_ID))
                .thenReturn(Optional.of(employeeUser()));
        when(userService.getUser(OTHER_ID))
                .thenReturn(Optional.of(sampleUser(OTHER_ID, "EMPLOYEE")));
        when(userService.getTeam(any()))
                .thenReturn(List.of(sampleUser(EMPLOYEE_ID, "EMPLOYEE")));
        when(userService.createUser(any()))
                .thenReturn(sampleUser("new-id", "EMPLOYEE"));
    }

    // ── GET /users — ADMIN only ───────────────────────────────────────────────

    @Nested
    @DisplayName("GET /users")
    class ListUsers {

        @Test
        @DisplayName("ADMIN token → 200 with user list")
        void admin_returns200() {
            given()
                .header("Authorization", "Bearer " + TestJwtHelper.adminToken(ADMIN_ID))
            .when()
                .get("/users")
            .then()
                .statusCode(200)
                .body("users", notNullValue());
        }

        @Test
        @DisplayName("MANAGER token → 403")
        void manager_returns403() {
            given()
                .header("Authorization", "Bearer " + TestJwtHelper.managerToken(MANAGER_ID))
            .when()
                .get("/users")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("EMPLOYEE token → 403")
        void employee_returns403() {
            given()
                .header("Authorization", "Bearer " + TestJwtHelper.employeeToken(EMPLOYEE_ID))
            .when()
                .get("/users")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("No token → 403")
        void noToken_returns403() {
            given()
            .when()
                .get("/users")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("Malformed token → 403 (ClaimsContext treats bad JWT as unauthenticated)")
        void malformedToken_returns403() {
            given()
                .header("Authorization", "Bearer this.is.notvalid")
            .when()
                .get("/users")
            .then()
                .statusCode(403);
        }
    }

    // ── POST /users — ADMIN only ──────────────────────────────────────────────

    @Nested
    @DisplayName("POST /users")
    class CreateUser {

        @Test
        @DisplayName("ADMIN token → 201")
        void admin_returns201() {
            given()
                .header("Authorization", "Bearer " + TestJwtHelper.adminToken(ADMIN_ID))
                .contentType(ContentType.JSON)
                .body(Map.of(
                    "email",      "new@example.com",
                    "firstName",  "New",
                    "lastName",   "User",
                    "role",       "EMPLOYEE",
                    "position",   "Engineer",
                    "status",     "Active",
                    "seniority",  "Engineer",
                    "discipline", "Software & DevOps Engineering"
                ))
            .when()
                .post("/users")
            .then()
                .statusCode(201);
        }

        @Test
        @DisplayName("MANAGER token → 403")
        void manager_returns403() {
            given()
                .header("Authorization", "Bearer " + TestJwtHelper.managerToken(MANAGER_ID))
                .contentType(ContentType.JSON)
                .body(Map.of(
                    "email",     "new@example.com",
                    "firstName", "New",
                    "lastName",  "User",
                    "role",      "EMPLOYEE",
                    "position",  "Engineer",
                    "status",    "Active"
                ))
            .when()
                .post("/users")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("EMPLOYEE token → 403")
        void employee_returns403() {
            given()
                .header("Authorization", "Bearer " + TestJwtHelper.employeeToken(EMPLOYEE_ID))
                .contentType(ContentType.JSON)
                .body(Map.of(
                    "email",     "new@example.com",
                    "firstName", "New",
                    "lastName",  "User",
                    "role",      "EMPLOYEE",
                    "position",  "Engineer",
                    "status",    "Active"
                ))
            .when()
                .post("/users")
            .then()
                .statusCode(403);
        }
    }

    // ── GET /users/{userId}/team ──────────────────────────────────────────────

    @Nested
    @DisplayName("GET /users/{userId}/team")
    class GetTeam {

        @Test
        @DisplayName("ADMIN → any manager's team → 200")
        void admin_anyTeam_returns200() {
            given()
                .header("Authorization", "Bearer " + TestJwtHelper.adminToken(ADMIN_ID))
            .when()
                .get("/users/" + MANAGER_ID + "/team")
            .then()
                .statusCode(200)
                .body("users",  notNullValue())
                .body("count", equalTo(1));
        }

        @Test
        @DisplayName("MANAGER → own team → 200")
        void manager_ownTeam_returns200() {
            given()
                .header("Authorization", "Bearer " + TestJwtHelper.managerToken(MANAGER_ID))
            .when()
                .get("/users/" + MANAGER_ID + "/team")
            .then()
                .statusCode(200);
        }

        @Test
        @DisplayName("MANAGER → another manager's team → 403")
        void manager_otherTeam_returns403() {
            given()
                .header("Authorization", "Bearer " + TestJwtHelper.managerToken(MANAGER_ID))
            .when()
                .get("/users/" + OTHER_ID + "/team")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("EMPLOYEE → 403")
        void employee_returns403() {
            given()
                .header("Authorization", "Bearer " + TestJwtHelper.employeeToken(EMPLOYEE_ID))
            .when()
                .get("/users/" + MANAGER_ID + "/team")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("No token → 403")
        void noToken_returns403() {
            given()
            .when()
                .get("/users/" + MANAGER_ID + "/team")
            .then()
                .statusCode(403);
        }
    }

    // ── GET /users/{userId}/manager ───────────────────────────────────────────

    @Nested
    @DisplayName("GET /users/{userId}/manager")
    class GetManager {

        @BeforeEach
        void setUpReportingChain() {
            // EMPLOYEE reports to MANAGER, MANAGER reports to ADMIN
            // OTHER_ID reports to nobody relevant
            when(userService.getUser(EMPLOYEE_ID))
                    .thenReturn(Optional.of(employeeUser()));         // reportingTo = MANAGER_ID
            when(userService.getUser(MANAGER_ID))
                    .thenReturn(Optional.of(managerUser()));          // reportingTo = ADMIN_ID
            when(userService.getUser(ADMIN_ID))
                    .thenReturn(Optional.of(sampleUser(ADMIN_ID, "ADMIN")));
            when(userService.getUser(OTHER_ID))
                    .thenReturn(Optional.of(sampleUser(OTHER_ID, "EMPLOYEE"))); // reportingTo = null
        }

        @Test
        @DisplayName("ADMIN → any user's manager → 200")
        void admin_anyUser_returns200() {
            given()
                .header("Authorization", "Bearer " + TestJwtHelper.adminToken(ADMIN_ID))
            .when()
                .get("/users/" + EMPLOYEE_ID + "/manager")
            .then()
                .statusCode(200)
                .body("userId", equalTo(MANAGER_ID));
        }

        @Test
        @DisplayName("EMPLOYEE → own manager → 200")
        void employee_ownManager_returns200() {
            given()
                .header("Authorization", "Bearer " + TestJwtHelper.employeeToken(EMPLOYEE_ID))
            .when()
                .get("/users/" + EMPLOYEE_ID + "/manager")
            .then()
                .statusCode(200)
                .body("userId", equalTo(MANAGER_ID));
        }

        @Test
        @DisplayName("EMPLOYEE → another user's manager → 403")
        void employee_otherManager_returns403() {
            given()
                .header("Authorization", "Bearer " + TestJwtHelper.employeeToken(EMPLOYEE_ID))
            .when()
                .get("/users/" + OTHER_ID + "/manager")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("MANAGER → own manager → 200")
        void manager_ownManager_returns200() {
            given()
                .header("Authorization", "Bearer " + TestJwtHelper.managerToken(MANAGER_ID))
            .when()
                .get("/users/" + MANAGER_ID + "/manager")
            .then()
                .statusCode(200)
                .body("userId", equalTo(ADMIN_ID));
        }

        @Test
        @DisplayName("MANAGER → their direct report's manager → 200")
        void manager_directReport_returns200() {
            // EMPLOYEE_ID.reportingTo = MANAGER_ID, so MANAGER_ID is allowed to see it
            given()
                .header("Authorization", "Bearer " + TestJwtHelper.managerToken(MANAGER_ID))
            .when()
                .get("/users/" + EMPLOYEE_ID + "/manager")
            .then()
                .statusCode(200);
        }

        @Test
        @DisplayName("MANAGER → unrelated user's manager → 403")
        void manager_unrelatedUser_returns403() {
            // OTHER_ID.reportingTo = null — not a direct report of MANAGER_ID
            given()
                .header("Authorization", "Bearer " + TestJwtHelper.managerToken(MANAGER_ID))
            .when()
                .get("/users/" + OTHER_ID + "/manager")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("No token → 403")
        void noToken_returns403() {
            given()
            .when()
                .get("/users/" + EMPLOYEE_ID + "/manager")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("User with no manager → 404")
        void userWithNoManager_returns404() {
            // OTHER_ID has reportingTo = null
            given()
                .header("Authorization", "Bearer " + TestJwtHelper.adminToken(ADMIN_ID))
            .when()
                .get("/users/" + OTHER_ID + "/manager")
            .then()
                .statusCode(404);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User sampleUser(String userId, String role) {
        return new User()
                .userId(userId)
                .email(userId + "@example.com")
                .firstName("Test")
                .lastName("User")
                .role(role)
                .position("Engineer")
                .status("Active");
    }

    /** Employee who reports to MANAGER_ID */
    private User employeeUser() {
        return new User()
                .userId(EMPLOYEE_ID)
                .email("emp@example.com")
                .firstName("Jane")
                .lastName("Doe")
                .role("EMPLOYEE")
                .position("Engineer")
                .status("Active")
                .reportingTo(MANAGER_ID);
    }

    /** Manager who reports to ADMIN_ID */
    private User managerUser() {
        return new User()
                .userId(MANAGER_ID)
                .email("mgr@example.com")
                .firstName("John")
                .lastName("Smith")
                .role("MANAGER")
                .position("Team Lead")
                .status("Active")
                .reportingTo(ADMIN_ID);
    }
}

package com.catalystflow.e2e;

import com.catalystflow.e2e.support.E2EConfig;
import com.catalystflow.e2e.support.E2EExtension;
import com.catalystflow.e2e.support.TestFixtures;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * E2E RBAC tests — verifies that every endpoint enforces the correct role boundaries
 * against real Cognito tokens and a real deployed API.
 *
 * Persona summary used in tests:
 *   admin    — ADMIN role; reports to nobody; has a DynamoDB record
 *   manager  — MANAGER role; reports to admin; has a DynamoDB record
 *   employee — EMPLOYEE role; reports to manager; has a DynamoDB record
 *
 * Each @Nested class mirrors one resource endpoint, with the same structure
 * as the existing AuthIntegrationTest so they are easy to compare.
 */
@ExtendWith(E2EExtension.class)
@DisplayName("RBAC E2E")
class UserRbacE2ETest {

    private static TestFixtures fx;

    @BeforeAll
    static void init(org.junit.jupiter.api.extension.ExtensionContext ctx) {
        fx = E2EExtension.fixtures(ctx);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /users
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /users")
    class ListUsers {

        @Test
        @DisplayName("ADMIN → 200 with user list")
        void admin_returns200() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
            .when()
                .get("/users")
            .then()
                .statusCode(200)
                .body("users", notNullValue());
        }

        @Test
        @DisplayName("MANAGER → 403")
        void manager_returns403() {
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
            .when()
                .get("/users")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("EMPLOYEE → 403")
        void employee_returns403() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/users")
            .then()
                .statusCode(403);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /users?role=
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /users?role=")
    class ListUsersByRole {

        @Test
        @DisplayName("ADMIN + role=EMPLOYEE → 200")
        void admin_filterByEmployee_returns200() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .queryParam("role", "EMPLOYEE")
            .when()
                .get("/users")
            .then()
                .statusCode(200)
                .body("users", notNullValue());
        }

        @Test
        @DisplayName("ADMIN + role=MANAGER → 200")
        void admin_filterByManager_returns200() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .queryParam("role", "MANAGER")
            .when()
                .get("/users")
            .then()
                .statusCode(200);
        }

        @Test
        @DisplayName("MANAGER + role=EMPLOYEE → 403 (role filter still requires ADMIN)")
        void manager_filterByRole_returns403() {
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .queryParam("role", "EMPLOYEE")
            .when()
                .get("/users")
            .then()
                .statusCode(403);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /users
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /users")
    class CreateUser {

        private static final String PROBE_EMAIL =
                "rbac-probe-" + E2EConfig.RUN_ID + "@catalystflow-test.invalid";

        private Map<String, Object> validBody() {
            return Map.of(
                "email",      PROBE_EMAIL,
                "firstName",  "Rbac",
                "lastName",   "Probe",
                "role",       "EMPLOYEE",
                "position",   "Tester",
                "status",     "Active",
                "seniority",  "Engineer",
                "discipline", "Software & DevOps Engineering"
            );
        }

        @Test
        @DisplayName("ADMIN → 201")
        void admin_returns201() {
            fx.registerForCleanup(null, PROBE_EMAIL);
            String newUserId = given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
                .body(validBody())
            .when()
                .post("/users")
            .then()
                .statusCode(201)
                .body("userId", notNullValue())
                .extract().path("userId");

            // Register the DynamoDB ID for full teardown at end of run
            fx.registerForCleanup(newUserId, null);

            // Deactivate immediately so the probe user cannot log in during the run
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
                .body(Map.of("status", "Inactive"))
            .when()
                .put("/users/" + newUserId);
        }

        @Test
        @DisplayName("MANAGER → 403")
        void manager_returns403() {
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(validBody())
            .when()
                .post("/users")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("EMPLOYEE → 403")
        void employee_returns403() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .contentType(ContentType.JSON)
                .body(validBody())
            .when()
                .post("/users")
            .then()
                .statusCode(403);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /users/{userId}
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /users/{userId}")
    class GetUser {

        @Test
        @DisplayName("ADMIN → any user → 200")
        void admin_anyUser_returns200() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
            .when()
                .get("/users/" + fx.employee.userId())
            .then()
                .statusCode(200)
                .body("userId", equalTo(fx.employee.userId()));
        }

        @Test
        @DisplayName("MANAGER → any user → 200 (no restriction on read)")
        void manager_anyUser_returns200() {
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
            .when()
                .get("/users/" + fx.employee.userId())
            .then()
                .statusCode(200)
                .body("userId", equalTo(fx.employee.userId()));
        }

        @Test
        @DisplayName("EMPLOYEE → own profile → 200")
        void employee_ownProfile_returns200() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/users/" + fx.employee.userId())
            .then()
                .statusCode(200)
                .body("userId", equalTo(fx.employee.userId()))
                .body("role",   equalTo("EMPLOYEE"));
        }

        @Test
        @DisplayName("Any authenticated user → non-existent userId → 404")
        void anyUser_nonExistentId_returns404() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
            .when()
                .get("/users/00000000-0000-0000-0000-000000000000")
            .then()
                .statusCode(404);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUT /users/{userId}
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /users/{userId}")
    class UpdateUser {

        @Test
        @DisplayName("ADMIN → 200")
        void admin_returns200() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
                .body(Map.of("position", "Senior Engineer"))
            .when()
                .put("/users/" + fx.employee.userId())
            .then()
                .statusCode(200);
        }

        @Test
        @DisplayName("MANAGER → 403")
        void manager_returns403() {
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(Map.of("position", "Junior Engineer"))
            .when()
                .put("/users/" + fx.employee.userId())
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("EMPLOYEE → 403")
        void employee_returns403() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .contentType(ContentType.JSON)
                .body(Map.of("position", "Junior Engineer"))
            .when()
                .put("/users/" + fx.employee.userId())
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("ADMIN → non-existent userId → 404")
        void admin_nonExistentUser_returns404() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
                .body(Map.of("position", "Ghost"))
            .when()
                .put("/users/00000000-0000-0000-0000-000000000000")
            .then()
                .statusCode(404);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /users/{userId}/team
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /users/{userId}/team")
    class GetTeam {

        @Test
        @DisplayName("ADMIN → any manager's team → 200")
        void admin_anyManagerTeam_returns200() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
            .when()
                .get("/users/" + fx.manager.userId() + "/team")
            .then()
                .statusCode(200)
                .body("users", notNullValue())
                .body("count", greaterThanOrEqualTo(1));
        }

        @Test
        @DisplayName("MANAGER → own team → 200")
        void manager_ownTeam_returns200() {
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
            .when()
                .get("/users/" + fx.manager.userId() + "/team")
            .then()
                .statusCode(200)
                .body("users", notNullValue());
        }

        @Test
        @DisplayName("MANAGER → another user's team → 403")
        void manager_otherTeam_returns403() {
            // Employee is not a manager — manager is requesting someone else's team
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
            .when()
                .get("/users/" + fx.employee.userId() + "/team")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("EMPLOYEE → any team → 403")
        void employee_returns403() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/users/" + fx.manager.userId() + "/team")
            .then()
                .statusCode(403);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /users/{userId}/manager
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /users/{userId}/manager")
    class GetManager {

        @Test
        @DisplayName("ADMIN → any user's manager → 200")
        void admin_anyUser_returns200() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
            .when()
                .get("/users/" + fx.employee.userId() + "/manager")
            .then()
                .statusCode(200)
                .body("userId", equalTo(fx.manager.userId()));
        }

        @Test
        @DisplayName("EMPLOYEE → own manager → 200")
        void employee_ownManager_returns200() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/users/" + fx.employee.userId() + "/manager")
            .then()
                .statusCode(200)
                .body("userId", equalTo(fx.manager.userId()));
        }

        @Test
        @DisplayName("EMPLOYEE → another user's manager → 403")
        void employee_otherUsersManager_returns403() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/users/" + fx.manager.userId() + "/manager")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("MANAGER → own manager → 200 (manager may resolve their own reporting chain)")
        void manager_ownManager_returns200() {
            // UserResource.getUserManager allows any authenticated caller to look up
            // their own manager chain — see UserResourceTest#testGetUserManager_Manager_OwnManager_Returns200
            // and AuthIntegrationTest "MANAGER → own manager → 200".
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
            .when()
                .get("/users/" + fx.manager.userId() + "/manager")
            .then()
                .statusCode(200);
        }

        @Test
        @DisplayName("MANAGER → direct report's manager → 200 (manager can see who their reports report to)")
        void manager_directReportManager_returns200() {
            // employee.reportingTo = manager, so manager is allowed to look up employee's manager
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
            .when()
                .get("/users/" + fx.employee.userId() + "/manager")
            .then()
                .statusCode(200);
        }

        @Test
        @DisplayName("MANAGER → unrelated user's manager → 403 (RBAC rejects before resolving the manager)")
        void manager_unrelatedUser_returns403() {
            // The API evaluates RBAC before lookup: a manager may only see the manager
            // chain for themselves or a direct report. admin is neither, so 403.
            // Mirrors UserResourceTest#testGetUserManager_Manager_UnrelatedUser_Returns403
            // and AuthIntegrationTest "MANAGER → unrelated user's manager → 403".
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
            .when()
                .get("/users/" + fx.admin.userId() + "/manager")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("ADMIN → user with no manager → 404")
        void admin_userWithNoManager_returns404() {
            // Admin has no reportingTo field
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
            .when()
                .get("/users/" + fx.admin.userId() + "/manager")
            .then()
                .statusCode(404);
        }
    }

}

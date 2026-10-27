package com.catalystflow.e2e;

import com.catalystflow.e2e.support.E2EConfig;
import com.catalystflow.e2e.support.E2EExtension;
import com.catalystflow.e2e.support.TestFixtures;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E lifecycle tests — creates a fresh user, exercises every mutation
 * endpoint against them, and verifies each change persisted.
 *
 * Tests are ordered because each step builds on the previous state.
 * The subject user is registered in TestFixtures so teardown deletes it.
 *
 * User under test:  a dedicated "lifecycle subject" created fresh in @BeforeAll.
 * Operated by:      admin token (lifecycle mutations are ADMIN-only).
 * Verified by:      reading back via GET /users/{id} and asserting fields.
 */
@ExtendWith(E2EExtension.class)
@DisplayName("User Lifecycle E2E")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserLifecycleE2ETest {

    private static TestFixtures fixtures;
    private static String       subjectId;
    private static String       subjectEmail;

    @BeforeAll
    static void createSubjectUser(org.junit.jupiter.api.extension.ExtensionContext ctx) {
        fixtures     = E2EExtension.fixtures(ctx);
        subjectEmail = "e2e-subject-" + E2EConfig.RUN_ID + "@catalystflow-test.invalid";

        fixtures.registerForCleanup(null, subjectEmail);

        Response response = given()
                .header("Authorization", "Bearer " + fixtures.admin.token())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "email",       subjectEmail,
                        "firstName",   "Lifecycle",
                        "lastName",    "Subject",
                        "role",        "EMPLOYEE",
                        "position",    "Junior Engineer",
                        "status",      "Active",
                        "reportingTo", fixtures.manager.userId(),
                        "seniority",   "Engineer",
                        "discipline",  "Software & DevOps Engineering"
                ))
                .when()
                .post("/users")
                .then()
                .statusCode(201)
                .extract().response();

        subjectId = response.path("userId");
        assertNotNull(subjectId, "POST /users must return a userId");

        // Register for full teardown (DynamoDB + Cognito) at end of run
        fixtures.registerForCleanup(subjectId, null);
    }

    // ── 1. Verify creation ────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("GET /users/{id} — newly created user — returns correct fields")
    void createdUser_hasCorrectFields() {
        given()
            .header("Authorization", "Bearer " + fixtures.admin.token())
        .when()
            .get("/users/" + subjectId)
        .then()
            .statusCode(200)
            .body("userId",      equalTo(subjectId))
            .body("email",       equalTo(subjectEmail))
            .body("firstName",   equalTo("Lifecycle"))
            .body("lastName",    equalTo("Subject"))
            .body("role",        equalTo("EMPLOYEE"))
            .body("position",    equalTo("Junior Engineer"))
            .body("status",      equalTo("Active"))
            .body("reportingTo", equalTo(fixtures.manager.userId()));
    }

    @Test
    @Order(2)
    @DisplayName("GET /users — newly created user — appears in admin list")
    void createdUser_appearsInList() {
        given()
            .header("Authorization", "Bearer " + fixtures.admin.token())
        .when()
            .get("/users")
        .then()
            .statusCode(200)
            .body("users.userId", hasItem(subjectId));
    }

    @Test
    @Order(3)
    @DisplayName("GET /users?role=EMPLOYEE — newly created user — appears in filtered list")
    void createdUser_appearsInRoleFilter() {
        given()
            .header("Authorization", "Bearer " + fixtures.admin.token())
            .queryParam("role", "EMPLOYEE")
        .when()
            .get("/users")
        .then()
            .statusCode(200)
            .body("users.userId", hasItem(subjectId));
    }

    // ── 2. Update name + position ─────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("PUT /users/{id} — update firstName, lastName, position — 200")
    void updateUser_nameAndPosition_returns200() {
        given()
            .header("Authorization", "Bearer " + fixtures.admin.token())
            .contentType(ContentType.JSON)
            .body(Map.of(
                "firstName", "Updated",
                "lastName",  "Name",
                "position",  "Senior Engineer"
            ))
        .when()
            .put("/users/" + subjectId)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(5)
    @DisplayName("GET /users/{id} — after name update — fields reflect changes")
    void afterNameUpdate_fieldsReflectChanges() {
        given()
            .header("Authorization", "Bearer " + fixtures.admin.token())
        .when()
            .get("/users/" + subjectId)
        .then()
            .statusCode(200)
            .body("firstName", equalTo("Updated"))
            .body("lastName",  equalTo("Name"))
            .body("position",  equalTo("Senior Engineer"));
    }

    // ── 3. Promote to MANAGER ─────────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("PUT /users/{id} — promote role to MANAGER — 200")
    void updateUser_promoteRole_returns200() {
        given()
            .header("Authorization", "Bearer " + fixtures.admin.token())
            .contentType(ContentType.JSON)
            .body(Map.of("role", "MANAGER"))
        .when()
            .put("/users/" + subjectId)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(7)
    @DisplayName("GET /users/{id} — after role promotion — role equals MANAGER")
    void afterRolePromotion_roleIsManager() {
        given()
            .header("Authorization", "Bearer " + fixtures.admin.token())
        .when()
            .get("/users/" + subjectId)
        .then()
            .statusCode(200)
            .body("role", equalTo("MANAGER"));
    }

    // ── 4. Deactivation ───────────────────────────────────────────────────────

    @Test
    @Order(8)
    @DisplayName("PUT /users/{id} — set status to Archived — 200")
    void deactivateUser_returns200() {
        given()
            .header("Authorization", "Bearer " + fixtures.admin.token())
            .contentType(ContentType.JSON)
            .body(Map.of("status", "Archived"))
        .when()
            .put("/users/" + subjectId)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(9)
    @DisplayName("GET /users/{id} — after deactivation — status is Archived")
    void afterDeactivation_statusIsInactive() {
        given()
            .header("Authorization", "Bearer " + fixtures.admin.token())
        .when()
            .get("/users/" + subjectId)
        .then()
            .statusCode(200)
            .body("status", equalTo("Archived"));
    }

    // ── 5. Manager relationship ───────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("GET /users/{id}/manager — subject user has a manager — 200")
    void subjectUser_hasManager_returns200() {
        given()
            .header("Authorization", "Bearer " + fixtures.admin.token())
        .when()
            .get("/users/" + subjectId + "/manager")
        .then()
            .statusCode(200)
            .body("userId", equalTo(fixtures.manager.userId()));
    }

    @Test
    @Order(11)
    @DisplayName("PUT /users/{id} — clear reportingTo — 200")
    void updateUser_clearReportingTo_returns200() {
        // Sending reportingTo as empty string removes the manager relationship
        given()
            .header("Authorization", "Bearer " + fixtures.admin.token())
            .contentType(ContentType.JSON)
            .body("{\"reportingTo\":\"\"}")
        .when()
            .put("/users/" + subjectId)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(12)
    @DisplayName("GET /users/{id}/manager — after clearing reportingTo — 404")
    void afterClearingReportingTo_managerReturns404() {
        given()
            .header("Authorization", "Bearer " + fixtures.admin.token())
        .when()
            .get("/users/" + subjectId + "/manager")
        .then()
            .statusCode(404);
    }
}

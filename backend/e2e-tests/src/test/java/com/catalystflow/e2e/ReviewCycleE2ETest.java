package com.catalystflow.e2e;

import com.catalystflow.e2e.support.E2EExtension;
import com.catalystflow.e2e.support.ReviewTestHelpers;
import com.catalystflow.e2e.support.TestFixtures;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * E2E tests for the review-cycle state machine and RBAC.
 *
 * Status flow:
 *   DRAFT → SCHEDULED → ACTIVE → COMPLETED
 *
 * Transition endpoints:
 *   POST /cycles/{id}/publish    DRAFT     → SCHEDULED
 *   POST /cycles/{id}/activate   SCHEDULED → ACTIVE
 *   POST /cycles/{id}/complete   ACTIVE    → COMPLETED   (ADMIN only)
 *
 * Mutations PUT / DELETE require ADMIN. COMPLETED cycles cannot be modified.
 * These tests are independent — each spins up a fresh template + cycle so
 * ordering and state pollution are not an issue.
 */
@ExtendWith(E2EExtension.class)
@DisplayName("Review Cycle E2E")
class ReviewCycleE2ETest {

    private static TestFixtures fx;
    private static String       sharedTemplateId;

    @BeforeAll
    static void init(org.junit.jupiter.api.extension.ExtensionContext ctx) {
        fx = E2EExtension.fixtures(ctx);
        // One template for the whole class — cycle tests do not mutate it.
        sharedTemplateId = ReviewTestHelpers.createTemplate(fx, fx.admin.token(), "SELF");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Happy-path lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Lifecycle: DRAFT → SCHEDULED → ACTIVE → COMPLETED")
    class Lifecycle {

        @Test
        @DisplayName("Created cycle starts in DRAFT")
        void created_isDraft() {
            String cycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), sharedTemplateId);

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
            .when()
                .get("/cycles/" + cycleId)
            .then()
                .statusCode(200)
                .body("cycleId", equalTo(cycleId))
                .body("status",  equalTo("DRAFT"));
        }

        @Test
        @DisplayName("DRAFT → publish → SCHEDULED")
        void draft_publish_returnsScheduled() {
            String cycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), sharedTemplateId);

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
            .when()
                .post("/cycles/" + cycleId + "/publish")
            .then()
                .statusCode(200)
                .body("status", equalTo("SCHEDULED"));
        }

        @Test
        @DisplayName("SCHEDULED → activate → ACTIVE")
        void scheduled_activate_returnsActive() {
            String cycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), sharedTemplateId);
            ReviewTestHelpers.publishCycle(fx.admin.token(), cycleId);

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
            .when()
                .post("/cycles/" + cycleId + "/activate")
            .then()
                .statusCode(200)
                .body("status", equalTo("ACTIVE"));
        }

        @Test
        @DisplayName("ACTIVE → complete → COMPLETED")
        void active_complete_returnsCompleted() {
            String cycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), sharedTemplateId);
            ReviewTestHelpers.publishCycle(fx.admin.token(), cycleId);
            ReviewTestHelpers.activateCycle(fx.admin.token(), cycleId);

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
            .when()
                .post("/cycles/" + cycleId + "/complete")
            .then()
                .statusCode(200)
                .body("status", equalTo("COMPLETED"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Invalid transitions
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Invalid transitions")
    class InvalidTransitions {

        @Test
        @DisplayName("DRAFT → activate (skipping publish) → 400")
        void draft_activate_returns400() {
            String cycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), sharedTemplateId);

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
            .when()
                .post("/cycles/" + cycleId + "/activate")
            .then()
                .statusCode(400);
        }

        @Test
        @DisplayName("SCHEDULED → publish again → 400")
        void scheduled_publishAgain_returns400() {
            String cycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), sharedTemplateId);
            ReviewTestHelpers.publishCycle(fx.admin.token(), cycleId);

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
            .when()
                .post("/cycles/" + cycleId + "/publish")
            .then()
                .statusCode(400);
        }

        @Test
        @DisplayName("COMPLETED cycle cannot be modified (PUT → 400)")
        void completed_putUpdate_returns400() {
            String cycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), sharedTemplateId);
            ReviewTestHelpers.publishCycle(fx.admin.token(), cycleId);
            ReviewTestHelpers.activateCycle(fx.admin.token(), cycleId);

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
            .when()
                .post("/cycles/" + cycleId + "/complete")
            .then()
                .statusCode(200);

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
                .body(Map.of("cycleTitle", "Renamed After Complete"))
            .when()
                .put("/cycles/" + cycleId)
            .then()
                .statusCode(400);
        }

        @Test
        @DisplayName("publish non-existent cycle → 404")
        void publish_nonExistent_returns404() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
            .when()
                .post("/cycles/nonexistent-cycle-id/publish")
            .then()
                .statusCode(404);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CRUD + RBAC
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CRUD + RBAC")
    class CrudRbac {

        @Test
        @DisplayName("GET /cycles → any authenticated user → 200")
        void list_employee_returns200() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/cycles")
            .then()
                .statusCode(200);
        }

        @Test
        @DisplayName("GET /cycles?status=DRAFT → 200")
        void list_filterByStatus_returns200() {
            String cycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), sharedTemplateId);

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .queryParam("status", "DRAFT")
            .when()
                .get("/cycles")
            .then()
                .statusCode(200);

            // Direct GET also returns the new cycle
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
            .when()
                .get("/cycles/" + cycleId)
            .then()
                .statusCode(200)
                .body("cycleId", equalTo(cycleId));
        }

        @Test
        @DisplayName("GET /cycles?status=BOGUS → 400")
        void list_invalidStatus_returns400() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .queryParam("status", "NOT_A_REAL_STATUS")
            .when()
                .get("/cycles")
            .then()
                .statusCode(400);
        }

        @Test
        @DisplayName("PUT /cycles/{id} — ADMIN → 200; MANAGER → 403; EMPLOYEE → 403")
        void put_rbac() {
            String cycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), sharedTemplateId);

            Map<String, Object> body = new HashMap<>();
            body.put("cycleTitle", "Renamed " + cycleId);

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .put("/cycles/" + cycleId)
            .then()
                .statusCode(200)
                .body("cycleTitle", equalTo(body.get("cycleTitle")));

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .put("/cycles/" + cycleId)
            .then()
                .statusCode(403);

            given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .put("/cycles/" + cycleId)
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("DELETE /cycles/{id} — ADMIN → 204")
        void delete_admin_returns204() {
            String cycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), sharedTemplateId);

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
            .when()
                .delete("/cycles/" + cycleId)
            .then()
                .statusCode(204);
        }

        @Test
        @DisplayName("DELETE /cycles/{id} — MANAGER → 403")
        void delete_manager_returns403() {
            String cycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), sharedTemplateId);

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
            .when()
                .delete("/cycles/" + cycleId)
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("complete /cycles/{id} — EMPLOYEE → 403 (admin-only transition)")
        void complete_employee_returns403() {
            String cycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), sharedTemplateId);
            ReviewTestHelpers.publishCycle(fx.admin.token(), cycleId);
            ReviewTestHelpers.activateCycle(fx.admin.token(), cycleId);

            given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .contentType(ContentType.JSON)
            .when()
                .post("/cycles/" + cycleId + "/complete")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("GET /cycles/health → 200 (public structure but auth still applies)")
        void health_returns200() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/cycles/health")
            .then()
                .statusCode(200)
                .body("message", notNullValue());
        }
    }
}

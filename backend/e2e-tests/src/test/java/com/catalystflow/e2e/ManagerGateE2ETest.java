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
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * E2E tests for the manager-review gate.
 *
 * The gate keeps a manager from creating or submitting a MANAGER review until:
 *   - the subject's SELF review is COMPLETED (or PUBLISHED), AND
 *   - every required PEER_360 row is COMPLETED (only if the cycle has a 360 milestone).
 *
 * These tests use a cycle WITHOUT any PEER_360 milestone, so {@code peer360Required = false}
 * and the gate flips open as soon as SELF is COMPLETED. The richer 360-required path is
 * exercised in {@link Peer360E2ETest} where the assignment fixtures already exist.
 *
 * Manager in {@link TestFixtures} is the direct manager of the employee (employee
 * reportingTo manager.userId), so manager-only RBAC paths succeed.
 */
@ExtendWith(E2EExtension.class)
@DisplayName("Manager Gate E2E")
class ManagerGateE2ETest {

    private static TestFixtures fx;
    private static String       cycleId;
    private static String       selfTemplateId;
    private static String       managerTemplateId;

    @BeforeAll
    static void bootstrap(org.junit.jupiter.api.extension.ExtensionContext ctx) {
        fx = E2EExtension.fixtures(ctx);

        selfTemplateId    = ReviewTestHelpers.createTemplate(fx, fx.admin.token(), "SELF");
        managerTemplateId = ReviewTestHelpers.createTemplate(fx, fx.admin.token(), "MANAGER");

        cycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), selfTemplateId);
        ReviewTestHelpers.publishCycle(fx.admin.token(), cycleId);
        ReviewTestHelpers.activateCycle(fx.admin.token(), cycleId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /reviews/manager-gate
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /reviews/manager-gate")
    class GateStatus {

        @Test
        @DisplayName("Before any SELF review exists → closed + reason self_missing")
        void noSelfReview_returnsSelfMissing() {
            String subjectId = fx.employee.userId();

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .queryParam("cycleId", cycleId)
                .queryParam("subjectUserId", subjectId)
            .when()
                .get("/reviews/manager-gate")
            .then()
                .statusCode(200)
                .body("open",          equalTo(false))
                .body("cycleId",       equalTo(cycleId))
                .body("subjectUserId", equalTo(subjectId))
                .body("reasons.type",  hasItem("self_missing"));
        }

        @Test
        @DisplayName("With SELF in DRAFT → closed + reason self_incomplete")
        void selfDraft_returnsSelfIncomplete() {
            // Fresh cycle so the previous test's lack-of-SELF state doesn't bleed in.
            String localCycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), selfTemplateId);
            ReviewTestHelpers.publishCycle(fx.admin.token(), localCycleId);
            ReviewTestHelpers.activateCycle(fx.admin.token(), localCycleId);
            ReviewTestHelpers.createSelfReview(
                    fx, fx.employee.token(), localCycleId, selfTemplateId, fx.employee.userId());

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .queryParam("cycleId", localCycleId)
                .queryParam("subjectUserId", fx.employee.userId())
            .when()
                .get("/reviews/manager-gate")
            .then()
                .statusCode(200)
                .body("open",         equalTo(false))
                .body("reasons.type", hasItem("self_incomplete"));
        }

        @Test
        @DisplayName("With SELF COMPLETED + cycle has no 360 milestone → gate OPEN")
        void selfCompleted_noPeer360Required_isOpen() {
            String localCycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), selfTemplateId);
            ReviewTestHelpers.publishCycle(fx.admin.token(), localCycleId);
            ReviewTestHelpers.activateCycle(fx.admin.token(), localCycleId);

            String reviewId = ReviewTestHelpers.createSelfReview(
                    fx, fx.employee.token(), localCycleId, selfTemplateId, fx.employee.userId());
            String questionId = ReviewTestHelpers.firstQuestionId(fx.admin.token(), selfTemplateId);
            int version = given()
                    .header("Authorization", "Bearer " + fx.employee.token())
                    .get("/reviews/" + reviewId)
                    .then().statusCode(200).extract().path("version");
            ReviewTestHelpers.submitReview(fx.employee.token(), reviewId, version, questionId, "Done")
                    .then().statusCode(200);

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .queryParam("cycleId", localCycleId)
                .queryParam("subjectUserId", fx.employee.userId())
            .when()
                .get("/reviews/manager-gate")
            .then()
                .statusCode(200)
                .body("open", equalTo(true))
                .body("selfReview.status", equalTo("COMPLETED"))
                .body("peer360.required",  equalTo(false));
        }

        @Test
        @DisplayName("GET /manager-gate — missing query params → 400")
        void missingParams_returns400() {
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
            .when()
                .get("/reviews/manager-gate")
            .then()
                .statusCode(400);
        }

        @Test
        @DisplayName("GET /manager-gate — employee caller → 403 (admin/manager only)")
        void employee_returns403() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .queryParam("cycleId", cycleId)
                .queryParam("subjectUserId", fx.employee.userId())
            .when()
                .get("/reviews/manager-gate")
            .then()
                .statusCode(403);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gate enforcement on POST /reviews (MANAGER type)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /reviews of type MANAGER")
    class CreateManagerReview {

        @Test
        @DisplayName("Gate closed (no SELF) → manager create still allowed → 201 (gate is advisory)")
        void gateClosed_managerCreateStillAllowed() {
            String localCycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), selfTemplateId);
            ReviewTestHelpers.publishCycle(fx.admin.token(), localCycleId);
            ReviewTestHelpers.activateCycle(fx.admin.token(), localCycleId);

            // The gate reports CLOSED for this subject — but it is advisory (a UI
            // heads-up), so it must NOT block the manager's create. See
            // ReviewResource.createReview ("Gate ... is ADVISORY, not a hard block").
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .queryParam("cycleId", localCycleId)
                .queryParam("subjectUserId", fx.employee.userId())
            .when()
                .get("/reviews/manager-gate")
            .then()
                .statusCode(200)
                .body("open",         equalTo(false))
                .body("reasons.type", hasItem("self_missing"));

            Map<String, Object> body = managerCreateBody(localCycleId, managerTemplateId);

            String reviewId = given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/reviews")
            .then()
                .statusCode(201)
                .body("reviewType", equalTo("MANAGER"))
                .extract().path("reviewId");
            fx.registerReviewForCleanup(reviewId);
        }

        @Test
        @DisplayName("Manager who is NOT the subject's direct manager → 403")
        void notSubjectsManager_returns403() {
            // admin acts as a non-manager third party — they're allowed to create
            // any review (admin bypasses RBAC), so we exercise the non-manager path
            // by using `manager`'s token against a SUBJECT they do NOT manage —
            // the admin user — which fails the isSubjectsManager check.
            String localCycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), selfTemplateId);
            ReviewTestHelpers.publishCycle(fx.admin.token(), localCycleId);
            ReviewTestHelpers.activateCycle(fx.admin.token(), localCycleId);

            Map<String, Object> body = new HashMap<>();
            body.put("cycleId",       localCycleId);
            body.put("templateId",    managerTemplateId);
            body.put("subjectUserId", fx.admin.userId());          // admin is NOT managed by manager
            body.put("authorUserId",  fx.manager.userId());
            body.put("reviewType",    "MANAGER");

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/reviews")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("Gate open (SELF completed, no 360 required) → MANAGER create → 201")
        void gateOpen_returns201() {
            String localCycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), selfTemplateId);
            ReviewTestHelpers.publishCycle(fx.admin.token(), localCycleId);
            ReviewTestHelpers.activateCycle(fx.admin.token(), localCycleId);
            completeSelf(localCycleId);

            Map<String, Object> body = managerCreateBody(localCycleId, managerTemplateId);

            String reviewId = given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/reviews")
            .then()
                .statusCode(201)
                .body("reviewType", equalTo("MANAGER"))
                .extract().path("reviewId");
            fx.registerReviewForCleanup(reviewId);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gate enforcement on submit (PUT status=COMPLETED on existing MANAGER review)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /reviews/{id} (MANAGER submit)")
    class SubmitManagerReview {

        @Test
        @DisplayName("MANAGER submit while gate closed → 200 (gate is advisory)")
        void submitWhileClosed_stillAllowed() {
            // Admin seeds a draft MANAGER row; the manager then submits it.
            String localCycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), selfTemplateId);
            ReviewTestHelpers.publishCycle(fx.admin.token(), localCycleId);
            ReviewTestHelpers.activateCycle(fx.admin.token(), localCycleId);

            String reviewId = ReviewTestHelpers.createReview(
                    fx, fx.admin.token(), localCycleId, managerTemplateId,
                    fx.employee.userId(), fx.manager.userId(), "MANAGER");

            int version = given()
                    .header("Authorization", "Bearer " + fx.manager.token())
                    .get("/reviews/" + reviewId)
                    .then().statusCode(200).extract().path("version");

            // SELF is still incomplete, so the gate is closed — but it is advisory,
            // so the manager's submit must succeed. See ReviewResource.updateReview.
            String questionId = ReviewTestHelpers.firstQuestionId(fx.admin.token(), managerTemplateId);
            Map<String, Object> answer = Map.of("questionId", questionId, "response", "Submitted ahead of prerequisites");
            Map<String, Object> body = new HashMap<>();
            body.put("status",  "COMPLETED");
            body.put("version", version);
            body.put("answers", List.of(answer));

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .put("/reviews/" + reviewId)
            .then()
                .statusCode(200)
                .body("status", equalTo("COMPLETED"));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void completeSelf(String localCycleId) {
        String selfReviewId = ReviewTestHelpers.createSelfReview(
                fx, fx.employee.token(), localCycleId, selfTemplateId, fx.employee.userId());
        String questionId = ReviewTestHelpers.firstQuestionId(fx.admin.token(), selfTemplateId);
        int version = given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .get("/reviews/" + selfReviewId)
                .then().statusCode(200).extract().path("version");
        ReviewTestHelpers.submitReview(fx.employee.token(), selfReviewId, version, questionId, "Self done")
                .then().statusCode(200);
    }

    private static Map<String, Object> managerCreateBody(String localCycleId, String managerTemplateId) {
        Map<String, Object> body = new HashMap<>();
        body.put("cycleId",       localCycleId);
        body.put("templateId",    managerTemplateId);
        body.put("subjectUserId", fx.employee.userId());
        body.put("authorUserId",  fx.manager.userId());
        body.put("reviewType",    "MANAGER");
        return body;
    }
}

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
import static org.hamcrest.Matchers.*;

/**
 * E2E tests for {@code POST /reviews/bundle/publish}.
 *
 * Contract:
 *   - All reviews for a (cycleId, subjectUserId) pair must be COMPLETED.
 *   - Any non-completed row causes 400 with a message naming the offender.
 *   - On success all rows transition COMPLETED → PUBLISHED in a single call.
 *   - Already-published rows are skipped (idempotent).
 *   - RBAC: admin / manager only — employee is 403.
 *
 * These tests rely on the manager being the direct manager of the employee
 * (TestFixtures bootstrap wires {@code employee.reportingTo = manager.userId}),
 * which is required for the manager-review create gate to pass.
 */
@ExtendWith(E2EExtension.class)
@DisplayName("Bundle Publish E2E")
class BundlePublishE2ETest {

    private static TestFixtures fx;
    private static String       selfTemplateId;
    private static String       managerTemplateId;

    @BeforeAll
    static void bootstrap(org.junit.jupiter.api.extension.ExtensionContext ctx) {
        fx = E2EExtension.fixtures(ctx);
        selfTemplateId    = ReviewTestHelpers.createTemplate(fx, fx.admin.token(), "SELF");
        managerTemplateId = ReviewTestHelpers.createTemplate(fx, fx.admin.token(), "MANAGER");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("SELF + MANAGER both COMPLETED → 200 + both PUBLISHED")
        void allCompleted_publishesBoth() {
            String cycleId = newActiveCycle();
            String selfReviewId    = completeSelf(cycleId);
            String managerReviewId = completeManager(cycleId);

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(bundleBody(cycleId, fx.employee.userId()))
            .when()
                .post("/reviews/bundle/publish")
            .then()
                .statusCode(200)
                .body("message", containsString("published"))
                .body("reviews.reviewId", hasItems(selfReviewId, managerReviewId))
                .body("reviews.status",   everyItem(equalTo("PUBLISHED")));

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .get("/reviews/" + selfReviewId)
                .then()
                .statusCode(200)
                .body("status", equalTo("PUBLISHED"))
                .body("publishedBy", equalTo(fx.manager.userId()));

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .get("/reviews/" + managerReviewId)
                .then()
                .statusCode(200)
                .body("status", equalTo("PUBLISHED"));
        }

        @Test
        @DisplayName("Already PUBLISHED rows are idempotent — second publish → 200")
        void idempotent_secondCall_returns200() {
            String cycleId = newActiveCycle();
            completeSelf(cycleId);
            completeManager(cycleId);

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(bundleBody(cycleId, fx.employee.userId()))
            .when()
                .post("/reviews/bundle/publish")
            .then()
                .statusCode(200);

            // Re-publish the same bundle — service is allowed to skip already-PUBLISHED rows.
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(bundleBody(cycleId, fx.employee.userId()))
            .when()
                .post("/reviews/bundle/publish")
            .then()
                .statusCode(200)
                .body("reviews.status", everyItem(equalTo("PUBLISHED")));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Negative paths
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Negative paths")
    class NegativePaths {

        @Test
        @DisplayName("SELF is DRAFT (not COMPLETED) → 400")
        void selfNotCompleted_returns400() {
            String cycleId = newActiveCycle();
            ReviewTestHelpers.createSelfReview(
                    fx, fx.employee.token(), cycleId, selfTemplateId, fx.employee.userId());
            // ↑ left in DRAFT — no submit call.

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(bundleBody(cycleId, fx.employee.userId()))
            .when()
                .post("/reviews/bundle/publish")
            .then()
                .statusCode(400)
                .body("message", anyOf(containsString("must be COMPLETED"),
                                       containsString("Cannot publish")));
        }

        @Test
        @DisplayName("No reviews for (cycle, subject) → 404")
        void noReviews_returns404() {
            String cycleId = newActiveCycle();

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(bundleBody(cycleId, fx.employee.userId()))
            .when()
                .post("/reviews/bundle/publish")
            .then()
                .statusCode(404);
        }

        @Test
        @DisplayName("Missing cycleId in request body → 400")
        void missingCycleId_returns400() {
            Map<String, Object> body = new HashMap<>();
            body.put("subjectUserId", fx.employee.userId());
            // cycleId omitted

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/reviews/bundle/publish")
            .then()
                .statusCode(400);
        }

        @Test
        @DisplayName("EMPLOYEE → 403")
        void employee_returns403() {
            String cycleId = newActiveCycle();
            completeSelf(cycleId);

            given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .contentType(ContentType.JSON)
                .body(bundleBody(cycleId, fx.employee.userId()))
            .when()
                .post("/reviews/bundle/publish")
            .then()
                .statusCode(403);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String newActiveCycle() {
        String cycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), selfTemplateId);
        ReviewTestHelpers.publishCycle(fx.admin.token(), cycleId);
        ReviewTestHelpers.activateCycle(fx.admin.token(), cycleId);
        return cycleId;
    }

    private static String completeSelf(String cycleId) {
        String reviewId = ReviewTestHelpers.createSelfReview(
                fx, fx.employee.token(), cycleId, selfTemplateId, fx.employee.userId());
        String questionId = ReviewTestHelpers.firstQuestionId(fx.admin.token(), selfTemplateId);
        int version = given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .get("/reviews/" + reviewId)
                .then().statusCode(200).extract().path("version");
        ReviewTestHelpers.submitReview(fx.employee.token(), reviewId, version, questionId, "Self done")
                .then().statusCode(200);
        return reviewId;
    }

    private static String completeManager(String cycleId) {
        String reviewId = ReviewTestHelpers.createReview(
                fx, fx.manager.token(), cycleId, managerTemplateId,
                fx.employee.userId(), fx.manager.userId(), "MANAGER");
        String questionId = ReviewTestHelpers.firstQuestionId(fx.admin.token(), managerTemplateId);
        int version = given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .get("/reviews/" + reviewId)
                .then().statusCode(200).extract().path("version");
        ReviewTestHelpers.submitReview(fx.manager.token(), reviewId, version, questionId, "Manager done")
                .then().statusCode(200);
        return reviewId;
    }

    private static Map<String, Object> bundleBody(String cycleId, String subjectUserId) {
        Map<String, Object> body = new HashMap<>();
        body.put("cycleId", cycleId);
        body.put("subjectUserId", subjectUserId);
        return body;
    }
}

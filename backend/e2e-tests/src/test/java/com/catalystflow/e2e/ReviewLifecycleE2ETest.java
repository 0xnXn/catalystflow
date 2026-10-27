package com.catalystflow.e2e;

import com.catalystflow.e2e.support.E2EExtension;
import com.catalystflow.e2e.support.ReviewTestHelpers;
import com.catalystflow.e2e.support.TestFixtures;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * E2E tests for the SELF review lifecycle and optimistic locking.
 *
 * Focus:
 *   - Employee can create own SELF review (and only own).
 *   - Drafting/submitting flow: DRAFT → IN_PROGRESS → COMPLETED.
 *   - Stale version PUT returns 409 (DDB ConditionalCheckFailedException).
 *   - Author-only update boundary (non-author employee → 403; admin → 200).
 *   - List shapes for /reviews/my-review and /reviews/assigned-to-me.
 *
 * The cycle used here is ACTIVE so peer/assignment surfacing rules apply, but
 * SELF rows always surface regardless of cycle status — these tests stay
 * within SELF-only behaviour.
 */
@ExtendWith(E2EExtension.class)
@DisplayName("Review Lifecycle E2E (SELF)")
class ReviewLifecycleE2ETest {

    private static TestFixtures fx;
    private static String       cycleId;
    private static String       selfTemplateId;
    private static String       firstQuestionId;

    @BeforeAll
    static void bootstrap(org.junit.jupiter.api.extension.ExtensionContext ctx) {
        fx = E2EExtension.fixtures(ctx);

        selfTemplateId  = ReviewTestHelpers.createTemplate(fx, fx.admin.token(), "SELF");
        firstQuestionId = ReviewTestHelpers.firstQuestionId(fx.admin.token(), selfTemplateId);

        cycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), selfTemplateId);
        ReviewTestHelpers.publishCycle(fx.admin.token(), cycleId);
        ReviewTestHelpers.activateCycle(fx.admin.token(), cycleId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Create
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /reviews")
    class CreateReview {

        @Test
        @DisplayName("EMPLOYEE → own SELF review → 201")
        void employee_ownSelf_returns201() {
            String reviewId = ReviewTestHelpers.createSelfReview(
                    fx, fx.employee.token(), cycleId, selfTemplateId, fx.employee.userId());
            assertNotNull(reviewId);

            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/reviews/" + reviewId)
            .then()
                .statusCode(200)
                .body("reviewType",     equalTo("SELF"))
                .body("subjectUserId",  equalTo(fx.employee.userId()))
                .body("authorUserId",   equalTo(fx.employee.userId()));
        }

        @Test
        @DisplayName("EMPLOYEE → SELF review for someone else → 403")
        void employee_selfForOther_returns403() {
            Map<String, Object> body = createBody(cycleId, selfTemplateId,
                    fx.manager.userId(), fx.employee.userId(), "SELF");
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/reviews")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("EMPLOYEE → MANAGER review → 403 (only admin/manager may create MANAGER reviews)")
        void employee_managerType_returns403() {
            Map<String, Object> body = createBody(cycleId, selfTemplateId,
                    fx.employee.userId(), fx.employee.userId(), "MANAGER");
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/reviews")
            .then()
                .statusCode(403);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Update + version locking
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /reviews/{id}")
    class UpdateReview {

        @Test
        @DisplayName("Author edits draft answers → 200 + version increments")
        void author_editAnswers_returns200() {
            String reviewId = ReviewTestHelpers.createSelfReview(
                    fx, fx.employee.token(), cycleId, selfTemplateId, fx.employee.userId());

            int initialVersion = fetchVersion(fx.employee.token(), reviewId);

            Map<String, Object> answer = Map.of(
                    "questionId", firstQuestionId,
                    "response",   "Initial draft answer"
            );
            Map<String, Object> body = new HashMap<>();
            body.put("status",  "IN_PROGRESS");
            body.put("version", initialVersion);
            body.put("answers", List.of(answer));

            given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .put("/reviews/" + reviewId)
            .then()
                .statusCode(200)
                .body("status",  equalTo("IN_PROGRESS"))
                .body("version", greaterThan(initialVersion));
        }

        @Test
        @DisplayName("Stale version → 409 conflict")
        void staleVersion_returns409() {
            String reviewId = ReviewTestHelpers.createSelfReview(
                    fx, fx.employee.token(), cycleId, selfTemplateId, fx.employee.userId());

            int initialVersion = fetchVersion(fx.employee.token(), reviewId);

            // First successful write — bumps the version
            Response first = ReviewTestHelpers.submitReview(
                    fx.employee.token(), reviewId, initialVersion, firstQuestionId, "Submitted answer");
            first.then().statusCode(200);

            // Second write with the now-stale version should be rejected
            Response stale = ReviewTestHelpers.submitReview(
                    fx.employee.token(), reviewId, initialVersion, firstQuestionId, "Second write");
            stale.then().statusCode(409);
        }

        @Test
        @DisplayName("Non-author / non-admin / non-direct-manager → 403")
        void nonAuthor_returns403() {
            // Employee creates the SELF review; another employee (admin in our pool plays
            // the role of "third party" via a fresh user) tries to update.
            String reviewId = ReviewTestHelpers.createSelfReview(
                    fx, fx.employee.token(), cycleId, selfTemplateId, fx.employee.userId());

            // The manager is the direct manager of employee in TestFixtures bootstrap.
            // To exercise the non-author + non-manager + non-admin branch we need a
            // caller who is none of the above. We use the admin's userId to act as
            // author (admin can update), then use manager's token in another scenario.
            //
            // Here: the *manager* is the direct manager of the subject (employee), so
            // they are allowed by `isSubjectsManager` — instead, swap subject/author
            // mapping by creating a review where manager is the subject. Employee is
            // NOT the manager's manager and NOT an admin, so 403.
            String mgrSelfReviewId = ReviewTestHelpers.createReview(
                    fx, fx.admin.token(), cycleId, selfTemplateId,
                    fx.manager.userId(), fx.manager.userId(), "SELF");

            Map<String, Object> body = Map.of("status", "DRAFT");
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .put("/reviews/" + mgrSelfReviewId)
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("Admin can update any review → 200")
        void admin_anyReview_returns200() {
            String reviewId = ReviewTestHelpers.createSelfReview(
                    fx, fx.employee.token(), cycleId, selfTemplateId, fx.employee.userId());

            int version = fetchVersion(fx.admin.token(), reviewId);

            Map<String, Object> body = new HashMap<>();
            body.put("status",  "DRAFT");
            body.put("version", version);
            body.put("overallComment", "Admin override");

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .put("/reviews/" + reviewId)
            .then()
                .statusCode(200)
                .body("overallComment", equalTo("Admin override"));
        }

        @Test
        @DisplayName("PUT non-existent review → 404")
        void nonExistent_returns404() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
                .body(Map.of("status", "DRAFT"))
            .when()
                .put("/reviews/00000000-0000-0000-0000-000000000000")
            .then()
                .statusCode(404);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // List helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /reviews/my-review and /reviews/assigned-to-me")
    class ListsForCaller {

        @Test
        @DisplayName("GET /reviews/my-review → caller's SELF review appears")
        void mine_includesSelfReview() {
            String reviewId = ReviewTestHelpers.createSelfReview(
                    fx, fx.employee.token(), cycleId, selfTemplateId, fx.employee.userId());

            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/reviews/my-review")
            .then()
                .statusCode(200)
                .body("reviews.reviewId", hasItem(reviewId));
        }

        @Test
        @DisplayName("GET /reviews/assigned-to-me → caller's own SELF review appears (they are author)")
        void assignedToMe_includesSelfReview() {
            String reviewId = ReviewTestHelpers.createSelfReview(
                    fx, fx.employee.token(), cycleId, selfTemplateId, fx.employee.userId());

            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/reviews/assigned-to-me")
            .then()
                .statusCode(200)
                .body("reviews.reviewId", hasItem(reviewId));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Delete
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /reviews/{id}")
    class DeleteReview {

        @Test
        @DisplayName("ADMIN → 204")
        void admin_returns204() {
            String reviewId = ReviewTestHelpers.createSelfReview(
                    fx, fx.employee.token(), cycleId, selfTemplateId, fx.employee.userId());

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
            .when()
                .delete("/reviews/" + reviewId)
            .then()
                .statusCode(204);
        }

        @Test
        @DisplayName("EMPLOYEE → 403")
        void employee_returns403() {
            String reviewId = ReviewTestHelpers.createSelfReview(
                    fx, fx.employee.token(), cycleId, selfTemplateId, fx.employee.userId());

            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .delete("/reviews/" + reviewId)
            .then()
                .statusCode(403);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int fetchVersion(String token, String reviewId) {
        Integer version = given()
                .header("Authorization", "Bearer " + token)
            .when()
                .get("/reviews/" + reviewId)
            .then()
                .statusCode(200)
                .extract().path("version");
        return version == null ? 0 : version;
    }

    private static Map<String, Object> createBody(String cycleId,
                                                  String templateId,
                                                  String subjectUserId,
                                                  String authorUserId,
                                                  String reviewType) {
        Map<String, Object> body = new HashMap<>();
        body.put("cycleId",        cycleId);
        body.put("templateId",     templateId);
        body.put("subjectUserId",  subjectUserId);
        body.put("authorUserId",   authorUserId);
        body.put("reviewType",     reviewType);
        return body;
    }
}

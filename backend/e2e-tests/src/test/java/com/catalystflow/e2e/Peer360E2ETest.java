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

/**
 * E2E tests for the 360 assignment endpoints under
 * {@code /cycles/{cycleId}/peer360-assignments} and the anonymity contract
 * that protects the subject from reading their own peer feedback.
 *
 * Persona mapping for these tests:
 *   subject = employee (managed by manager)
 *   author  = admin    (acts as the third-party peer reviewer)
 *   creator = manager  (or admin in some cases)
 *
 * Admin is the only "third" user the standard fixtures expose; the service
 * enforces that the assignment creator cannot also be the peer author, so we
 * always use a different identity for the two roles.
 */
@ExtendWith(E2EExtension.class)
@DisplayName("Peer 360 Assignment + Anonymity E2E")
class Peer360E2ETest {

    private static TestFixtures fx;
    private static String       peerTemplateId;

    @BeforeAll
    static void bootstrap(org.junit.jupiter.api.extension.ExtensionContext ctx) {
        fx = E2EExtension.fixtures(ctx);
        peerTemplateId = ReviewTestHelpers.createTemplate(fx, fx.admin.token(), "PEER_360");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Create
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /cycles/{cycleId}/peer360-assignments")
    class CreateAssignments {

        @Test
        @DisplayName("MANAGER assigns admin as peer for employee → 200 with created row")
        void manager_createsAssignment_returns200() {
            String cycleId = activeCycleForPeer();

            Map<String, Object> body = createBody(fx.employee.userId(), List.of(fx.admin.userId()));

            Response r = given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/cycles/" + cycleId + "/peer360-assignments");

            r.then()
                .statusCode(200)
                .body("created.size()", greaterThanOrEqualTo(1))
                .body("created[0].reviewType",    equalTo("PEER_360"))
                .body("created[0].subjectUserId", equalTo(fx.employee.userId()))
                .body("created[0].authorUserId",  equalTo(fx.admin.userId()));

            // Persist for teardown
            String reviewId = r.then().extract().path("created[0].reviewId");
            fx.registerReviewForCleanup(reviewId);
        }

        @Test
        @DisplayName("Re-POSTing same author → 200 with admin in skipped, no new row")
        void duplicate_returnsSkipped() {
            String cycleId = activeCycleForPeer();

            Map<String, Object> body = createBody(fx.employee.userId(), List.of(fx.admin.userId()));
            String firstId = given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(body)
                .post("/cycles/" + cycleId + "/peer360-assignments")
                .then().statusCode(200).extract().path("created[0].reviewId");
            fx.registerReviewForCleanup(firstId);

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/cycles/" + cycleId + "/peer360-assignments")
            .then()
                .statusCode(200)
                .body("created", hasSize(0))
                .body("skipped[0].reviewId", equalTo(firstId));
        }

        @Test
        @DisplayName("EMPLOYEE → 403")
        void employee_returns403() {
            String cycleId = activeCycleForPeer();

            given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .contentType(ContentType.JSON)
                .body(createBody(fx.employee.userId(), List.of(fx.admin.userId())))
            .when()
                .post("/cycles/" + cycleId + "/peer360-assignments")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("Subject == author → 400 (cannot self-peer)")
        void subjectAsAuthor_returns400() {
            String cycleId = activeCycleForPeer();

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(createBody(fx.employee.userId(), List.of(fx.employee.userId())))
            .when()
                .post("/cycles/" + cycleId + "/peer360-assignments")
            .then()
                .statusCode(400);
        }

        @Test
        @DisplayName("Author == creating manager → 400 (manager cannot also be a peer)")
        void managerAsAuthor_returns400() {
            String cycleId = activeCycleForPeer();

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(createBody(fx.employee.userId(), List.of(fx.manager.userId())))
            .when()
                .post("/cycles/" + cycleId + "/peer360-assignments")
            .then()
                .statusCode(400);
        }

        @Test
        @DisplayName("Cycle in DRAFT → 400 (must be SCHEDULED or ACTIVE)")
        void draftCycle_returns400() {
            String draftCycleId = ReviewTestHelpers.createDraftCycle(
                    fx, fx.admin.token(), peerTemplateId);

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(createBody(fx.employee.userId(), List.of(fx.admin.userId())))
            .when()
                .post("/cycles/" + draftCycleId + "/peer360-assignments")
            .then()
                .statusCode(400);
        }

        @Test
        @DisplayName("Wrong template type (SELF) → 400")
        void wrongTemplateType_returns400() {
            String cycleId        = activeCycleForPeer();
            String selfTemplateId = ReviewTestHelpers.createTemplate(fx, fx.admin.token(), "SELF");

            Map<String, Object> body = new HashMap<>();
            body.put("subjectUserId", fx.employee.userId());
            body.put("authorUserIds", List.of(fx.admin.userId()));
            body.put("templateId",    selfTemplateId);

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/cycles/" + cycleId + "/peer360-assignments")
            .then()
                .statusCode(400);
        }

        @Test
        @DisplayName("Empty authorUserIds → 400")
        void emptyAuthors_returns400() {
            String cycleId = activeCycleForPeer();

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(createBody(fx.employee.userId(), List.of()))
            .when()
                .post("/cycles/" + cycleId + "/peer360-assignments")
            .then()
                .statusCode(400);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // List
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /cycles/{cycleId}/peer360-assignments")
    class ListAssignments {

        @Test
        @DisplayName("MANAGER → list returns assignments")
        void manager_returnsAssignments() {
            String cycleId = activeCycleForPeer();
            String reviewId = createAssignmentReturningId(cycleId);

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .queryParam("subjectUserId", fx.employee.userId())
            .when()
                .get("/cycles/" + cycleId + "/peer360-assignments")
            .then()
                .statusCode(200)
                .body("reviewId", hasItem(reviewId))
                .body("reviewType", everyItem(equalTo("PEER_360")));
        }

        @Test
        @DisplayName("Subject themselves → 403 (anonymity)")
        void subjectThemselves_returns403() {
            String cycleId = activeCycleForPeer();
            createAssignmentReturningId(cycleId);

            given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .queryParam("subjectUserId", fx.employee.userId())
            .when()
                .get("/cycles/" + cycleId + "/peer360-assignments")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("Missing subjectUserId → 400")
        void missingSubjectUserId_returns400() {
            String cycleId = activeCycleForPeer();
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
            .when()
                .get("/cycles/" + cycleId + "/peer360-assignments")
            .then()
                .statusCode(400);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Anonymity on single-review GET
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /reviews/{id} — anonymity")
    class Anonymity {

        @Test
        @DisplayName("Subject cannot read their own PEER_360 review → 403")
        void subject_cannotReadOwnPeer360() {
            String cycleId  = activeCycleForPeer();
            String reviewId = createAssignmentReturningId(cycleId);

            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/reviews/" + reviewId)
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("Manager CAN read PEER_360 about their direct report → 200")
        void manager_canReadDirectReportPeer360() {
            String cycleId  = activeCycleForPeer();
            String reviewId = createAssignmentReturningId(cycleId);

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
            .when()
                .get("/reviews/" + reviewId)
            .then()
                .statusCode(200)
                .body("reviewType", equalTo("PEER_360"));
        }

        @Test
        @DisplayName("/reviews/my-review never surfaces PEER_360 rows")
        void mine_doesNotIncludePeer360() {
            String cycleId  = activeCycleForPeer();
            String reviewId = createAssignmentReturningId(cycleId);

            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/reviews/my-review")
            .then()
                .statusCode(200)
                .body("reviews.reviewId", not(hasItem(reviewId)));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Delete (NOT_STARTED only)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /cycles/{cycleId}/peer360-assignments/{reviewId}")
    class DeleteAssignment {

        @Test
        @DisplayName("NOT_STARTED → 204; subsequent GET → 404")
        void notStarted_returns204() {
            String cycleId  = activeCycleForPeer();
            String reviewId = createAssignmentReturningId(cycleId);

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
            .when()
                .delete("/cycles/" + cycleId + "/peer360-assignments/" + reviewId)
            .then()
                .statusCode(204);

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
            .when()
                .get("/reviews/" + reviewId)
            .then()
                .statusCode(404);
        }

        @Test
        @DisplayName("Non-existent reviewId → 404")
        void nonExistent_returns404() {
            String cycleId = activeCycleForPeer();
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
            .when()
                .delete("/cycles/" + cycleId + "/peer360-assignments/00000000-0000-0000-0000-000000000000")
            .then()
                .statusCode(404);
        }

        @Test
        @DisplayName("Wrong cycle scoping (assignment exists but cycleId in path mismatches) → 404")
        void wrongCycle_returns404() {
            String realCycleId = activeCycleForPeer();
            String reviewId    = createAssignmentReturningId(realCycleId);

            String otherCycleId = ReviewTestHelpers.createDraftCycle(
                    fx, fx.admin.token(), peerTemplateId);

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
            .when()
                .delete("/cycles/" + otherCycleId + "/peer360-assignments/" + reviewId)
            .then()
                .statusCode(404);
        }

        @Test
        @DisplayName("EMPLOYEE → 403")
        void employee_returns403() {
            String cycleId  = activeCycleForPeer();
            String reviewId = createAssignmentReturningId(cycleId);

            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .delete("/cycles/" + cycleId + "/peer360-assignments/" + reviewId)
            .then()
                .statusCode(403);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Spins up a fresh cycle (DRAFT → SCHEDULED → ACTIVE) so 360 assignments can be created. */
    private static String activeCycleForPeer() {
        String cycleId = ReviewTestHelpers.createDraftCycle(fx, fx.admin.token(), peerTemplateId);
        ReviewTestHelpers.publishCycle(fx.admin.token(), cycleId);
        ReviewTestHelpers.activateCycle(fx.admin.token(), cycleId);
        return cycleId;
    }

    private static String createAssignmentReturningId(String cycleId) {
        String reviewId = given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(createBody(fx.employee.userId(), List.of(fx.admin.userId())))
            .when()
                .post("/cycles/" + cycleId + "/peer360-assignments")
            .then()
                .statusCode(200)
                .extract().path("created[0].reviewId");
        fx.registerReviewForCleanup(reviewId);
        return reviewId;
    }

    private static Map<String, Object> createBody(String subjectUserId, List<String> authorUserIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("subjectUserId", subjectUserId);
        body.put("authorUserIds", authorUserIds);
        body.put("templateId",    peerTemplateId);
        return body;
    }
}

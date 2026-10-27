package com.catalystflow.e2e;

import com.catalystflow.e2e.support.E2EConfig;
import com.catalystflow.e2e.support.E2EExtension;
import com.catalystflow.e2e.support.ReviewTestHelpers;
import com.catalystflow.e2e.support.TestFixtures;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Full-narrative E2E for a single review-cycle workflow.
 *
 * Walks one (cycle, subject) pair through every state transition that ships:
 *   1. Admin   — creates templates + cycle, publishes, activates.
 *   2. Employee — writes + submits SELF review.
 *   3. Manager — assigns a peer reviewer.
 *      Peer    — completes the PEER_360.
 *   4. Manager — confirms the gate is open.
 *   5. Manager — writes + submits MANAGER review.
 *   6. Manager — publishes the bundle.
 *   7. Admin   — completes the cycle and confirms the COMPLETED-frozen invariant.
 *
 * This is an integration smoke of the focused E2E files
 * ({@link ReviewTemplateE2ETest}, {@link ReviewCycleE2ETest},
 * {@link ReviewLifecycleE2ETest}, {@link Peer360E2ETest},
 * {@link ManagerGateE2ETest}, {@link BundlePublishE2ETest}) — it proves they
 * compose without re-asserting the edge-case matrix each of those covers.
 * Deliberately AI-free; AI smoke lives in {@link AiReviewE2ETest}.
 */
@ExtendWith(E2EExtension.class)
@DisplayName("Review Cycle Workflow (end-to-end narrative)")
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class ReviewCycleWorkflowE2ETest {

    private static TestFixtures fx;

    // ── Workflow-wide state — populated in @BeforeAll and the early phases ────
    private static String selfTemplateId;
    private static String managerTemplateId;
    private static String peerTemplateId;
    private static String selfQuestionId;
    private static String managerQuestionId;
    private static String peerQuestionId;

    private static String cycleId;
    private static String selfReviewId;
    private static String peerReviewId;
    private static String managerReviewId;

    /** Cycle date range — kept identical to ReviewTestHelpers so milestones fit. */
    private static final String CYCLE_START = ReviewTestHelpers.CYCLE_START;
    private static final String CYCLE_DUE   = ReviewTestHelpers.CYCLE_DUE;

    @BeforeAll
    static void bootstrap(org.junit.jupiter.api.extension.ExtensionContext ctx) {
        fx = E2EExtension.fixtures(ctx);

        selfTemplateId    = ReviewTestHelpers.createTemplate(fx, fx.admin.token(), "SELF");
        managerTemplateId = ReviewTestHelpers.createTemplate(fx, fx.admin.token(), "MANAGER");
        peerTemplateId    = ReviewTestHelpers.createTemplate(fx, fx.admin.token(), "PEER_360");

        selfQuestionId    = ReviewTestHelpers.firstQuestionId(fx.admin.token(), selfTemplateId);
        managerQuestionId = ReviewTestHelpers.firstQuestionId(fx.admin.token(), managerTemplateId);
        peerQuestionId    = ReviewTestHelpers.firstQuestionId(fx.admin.token(), peerTemplateId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Admin bootstraps the cycle with BOTH a SELF and a PEER_360 milestone
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @Order(1)
    @DisplayName("1. Admin bootstrap — templates + cycle + publish + activate")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class AdminBootstrap {

        @Test
        @Order(1)
        @DisplayName("POST /cycles — cycle is DRAFT with both milestones embedded")
        void createCycleWithBothMilestones() {
            // Use direct REST here rather than ReviewTestHelpers.createDraftCycle
            // because the workflow needs TWO milestones (the helper creates one
            // SELF-only) and richer templateSnapshots so the manager-gate
            // correctly identifies peer360Required=true downstream.
            Map<String, Object> selfMilestone = milestone(
                    "m-self-" + uniqueSuffix(), "Self Review", "EMPLOYEE",
                    selfTemplateId, "SELF");
            Map<String, Object> peerMilestone = milestone(
                    "m-peer-" + uniqueSuffix(), "Peer Feedback", "EMPLOYEE",
                    peerTemplateId, "PEER_360");

            Map<String, Object> body = new HashMap<>();
            body.put("cycleTitle", "E2E Workflow " + uniqueSuffix());
            body.put("templateId", selfTemplateId);
            body.put("startDate",  CYCLE_START);
            body.put("dueDate",    CYCLE_DUE);
            body.put("milestones", List.of(selfMilestone, peerMilestone));

            cycleId = given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/cycles")
            .then()
                .statusCode(201)
                .body("status", equalTo("DRAFT"))
                .body("milestones.size()", equalTo(2))
                .extract().path("cycleId");

            assertNotNull(cycleId, "POST /cycles must return a cycleId");
            fx.registerCycleForCleanup(cycleId);
        }

        @Test
        @Order(2)
        @DisplayName("POST /cycles/{id}/publish — DRAFT → SCHEDULED")
        void publishCycle() {
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
        @Order(3)
        @DisplayName("POST /cycles/{id}/activate — SCHEDULED → ACTIVE")
        void activateCycle() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
            .when()
                .post("/cycles/" + cycleId + "/activate")
            .then()
                .statusCode(200)
                .body("status", equalTo("ACTIVE"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Employee writes and submits the SELF review
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @Order(2)
    @DisplayName("2. Employee — SELF review create, draft, submit")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SelfReview {

        @Test
        @Order(1)
        @DisplayName("POST /reviews — employee creates own SELF row")
        void employeeCreatesSelfReview() {
            selfReviewId = ReviewTestHelpers.createSelfReview(
                    fx, fx.employee.token(), cycleId, selfTemplateId, fx.employee.userId());
            assertNotNull(selfReviewId);
        }

        @Test
        @Order(2)
        @DisplayName("GET /reviews/my-review — new SELF row appears for the employee")
        void selfReviewVisibleInMine() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/reviews/my-review")
            .then()
                .statusCode(200)
                .body("reviews.reviewId", hasItem(selfReviewId));
        }

        @Test
        @Order(3)
        @DisplayName("PUT /reviews/{id} — draft answers (status=IN_PROGRESS)")
        void draftSelfAnswers() {
            int version = fetchVersion(fx.employee.token(), selfReviewId);

            Map<String, Object> answer = Map.of(
                    "questionId", selfQuestionId,
                    "response",   "Mid-flow draft answer.");

            Map<String, Object> body = new HashMap<>();
            body.put("status",  "IN_PROGRESS");
            body.put("version", version);
            body.put("answers", List.of(answer));

            given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .put("/reviews/" + selfReviewId)
            .then()
                .statusCode(200)
                .body("status",  equalTo("IN_PROGRESS"))
                .body("version", greaterThan(version));
        }

        @Test
        @Order(4)
        @DisplayName("PUT /reviews/{id} — submit (status=COMPLETED)")
        void submitSelfReview() {
            int version = fetchVersion(fx.employee.token(), selfReviewId);

            Response submit = ReviewTestHelpers.submitReview(
                    fx.employee.token(), selfReviewId, version,
                    selfQuestionId, "Final self-review answer.");

            submit.then()
                .statusCode(200)
                .body("status",      equalTo("COMPLETED"))
                .body("submittedAt", notNullValue());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Manager assigns a peer; peer (admin acts as third party) completes it
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @Order(3)
    @DisplayName("3. Peer 360 — manager assigns, peer completes, anonymity holds")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class Peer360 {

        @Test
        @Order(1)
        @DisplayName("POST /cycles/{id}/peer360-assignments — manager assigns admin as peer")
        void managerAssignsPeer() {
            // admin acts as the "third party" peer here because manager is the
            // assignment creator (and the service forbids self-assignment) and
            // employee is the subject (and the service forbids self-peer).
            Map<String, Object> body = new HashMap<>();
            body.put("subjectUserId", fx.employee.userId());
            body.put("authorUserIds", List.of(fx.admin.userId()));
            body.put("templateId",    peerTemplateId);

            peerReviewId = given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/cycles/" + cycleId + "/peer360-assignments")
            .then()
                .statusCode(200)
                .body("created.size()", equalTo(1))
                .body("created[0].reviewType",    equalTo("PEER_360"))
                .body("created[0].subjectUserId", equalTo(fx.employee.userId()))
                .body("created[0].authorUserId",  equalTo(fx.admin.userId()))
                .extract().path("created[0].reviewId");

            assertNotNull(peerReviewId);
            fx.registerReviewForCleanup(peerReviewId);
        }

        @Test
        @Order(2)
        @DisplayName("Anonymity — subject (employee) cannot read own PEER_360 row")
        void subjectCannotReadOwnPeer360() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/reviews/" + peerReviewId)
            .then()
                .statusCode(403);
        }

        @Test
        @Order(3)
        @DisplayName("Anonymity — /reviews/my-review never surfaces the PEER_360 row")
        void mineHidesPeer360() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/reviews/my-review")
            .then()
                .statusCode(200)
                .body("reviews.reviewId", not(hasItem(peerReviewId)));
        }

        @Test
        @Order(4)
        @DisplayName("PUT /reviews/{id} — peer (admin) submits the 360 row")
        void peerCompletesAssignment() {
            int version = fetchVersion(fx.admin.token(), peerReviewId);
            Response submit = ReviewTestHelpers.submitReview(
                    fx.admin.token(), peerReviewId, version,
                    peerQuestionId, "Peer feedback for the employee.");

            submit.then()
                .statusCode(200)
                .body("status",      equalTo("COMPLETED"))
                .body("submittedAt", notNullValue());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Manager gate is now open
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @Order(4)
    @DisplayName("4. Manager gate — open after SELF + PEER_360 are both COMPLETED")
    class ManagerGate {

        @Test
        @DisplayName("GET /reviews/manager-gate — open=true, peer360 required + satisfied")
        void gateIsOpen() {
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .queryParam("cycleId",       cycleId)
                .queryParam("subjectUserId", fx.employee.userId())
            .when()
                .get("/reviews/manager-gate")
            .then()
                .statusCode(200)
                .body("open",                     equalTo(true))
                .body("selfReview.status",        equalTo("COMPLETED"))
                .body("peer360.required",         equalTo(true))
                .body("peer360.totalAssigned",    equalTo(1))
                .body("peer360.completedCount",   equalTo(1))
                .body("reasons",                  hasSize(0));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Manager writes and submits the MANAGER review
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @Order(5)
    @DisplayName("5. Manager — create and submit the MANAGER review")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ManagerReview {

        @Test
        @Order(1)
        @DisplayName("POST /reviews — manager creates MANAGER row (gate open)")
        void managerCreatesReview() {
            managerReviewId = ReviewTestHelpers.createReview(
                    fx, fx.manager.token(),
                    cycleId, managerTemplateId,
                    fx.employee.userId(), fx.manager.userId(), "MANAGER");
            assertNotNull(managerReviewId);
        }

        @Test
        @Order(2)
        @DisplayName("PUT /reviews/{id} — manager submits the MANAGER review")
        void managerSubmitsReview() {
            int version = fetchVersion(fx.manager.token(), managerReviewId);
            Response submit = ReviewTestHelpers.submitReview(
                    fx.manager.token(), managerReviewId, version,
                    managerQuestionId, "Manager's narrative summary.");

            submit.then()
                .statusCode(200)
                .body("status",      equalTo("COMPLETED"))
                .body("submittedAt", notNullValue());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Bundle publish — atomic transition of all rows to PUBLISHED
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @Order(6)
    @DisplayName("6. Bundle publish — all rows for the subject become PUBLISHED")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class BundlePublish {

        @Test
        @Order(1)
        @DisplayName("POST /reviews/bundle/publish — every row PUBLISHED, publishedBy=manager")
        void bundlePublish() {
            Map<String, Object> body = Map.of(
                    "cycleId",       cycleId,
                    "subjectUserId", fx.employee.userId());

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/reviews/bundle/publish")
            .then()
                .statusCode(200)
                .body("message",                containsString("published"))
                .body("reviews.size()",         greaterThanOrEqualTo(2))
                .body("reviews.reviewId",       hasItems(selfReviewId, managerReviewId, peerReviewId))
                .body("reviews.status",         everyItem(equalTo("PUBLISHED")))
                .body("reviews.publishedBy",    everyItem(equalTo(fx.manager.userId())));
        }

        @Test
        @Order(2)
        @DisplayName("GET /reviews/{id} — SELF row reads back as PUBLISHED")
        void selfReadsBackPublished() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
            .when()
                .get("/reviews/" + selfReviewId)
            .then()
                .statusCode(200)
                .body("status",      equalTo("PUBLISHED"))
                .body("publishedAt", notNullValue());
        }

        @Test
        @Order(3)
        @DisplayName("GET /reviews/{id} — MANAGER row reads back as PUBLISHED")
        void managerReadsBackPublished() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
            .when()
                .get("/reviews/" + managerReviewId)
            .then()
                .statusCode(200)
                .body("status",      equalTo("PUBLISHED"))
                .body("publishedAt", notNullValue());
        }

        @Test
        @Order(4)
        @DisplayName("GET /reviews?subjectUserId=…&cycleId=… — overallStatus=PUBLISHED")
        void overallStatusIsPublished() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .queryParam("subjectUserId", fx.employee.userId())
                .queryParam("cycleId",       cycleId)
            .when()
                .get("/reviews")
            .then()
                .statusCode(200)
                .body("overallStatus", equalTo("PUBLISHED"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Cycle close-out — admin completes; frozen-cycle invariant fires
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @Order(7)
    @DisplayName("7. Cycle close-out — admin completes, COMPLETED is frozen")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class CycleClose {

        @Test
        @Order(1)
        @DisplayName("POST /cycles/{id}/complete — admin closes the cycle")
        void adminCompletesCycle() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
            .when()
                .post("/cycles/" + cycleId + "/complete")
            .then()
                .statusCode(200)
                .body("status", equalTo("COMPLETED"));
        }

        @Test
        @Order(2)
        @DisplayName("PUT /cycles/{id} on a COMPLETED cycle — 400 (frozen)")
        void completedCycleIsFrozen() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
                .body(Map.of("cycleTitle", "Should-Not-Stick"))
            .when()
                .put("/cycles/" + cycleId)
            .then()
                .statusCode(400);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds one workflow milestone. Provides a minimal templateSnapshot
     * (templateId + templateName + reviewType) — the manager-gate logic only
     * needs reviewType to decide whether peer-360 is required, and providing
     * the templateId + name keeps the shape close enough to production rows
     * that any future snapshot-reading consumer won't NPE.
     */
    private static Map<String, Object> milestone(String milestoneId,
                                                 String name,
                                                 String assignedRole,
                                                 String templateId,
                                                 String reviewType) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("templateId",   templateId);
        snapshot.put("templateName", name);
        snapshot.put("reviewType",   reviewType);

        Map<String, Object> milestone = new HashMap<>();
        milestone.put("milestoneId",      milestoneId);
        milestone.put("name",             name);
        milestone.put("assignedRoles",    List.of(assignedRole));
        milestone.put("startDate",        CYCLE_START);
        milestone.put("endDate",          CYCLE_DUE);
        milestone.put("templateSnapshot", snapshot);
        return milestone;
    }

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

    private static String uniqueSuffix() {
        return E2EConfig.RUN_ID + "-" + UUID.randomUUID().toString().substring(0, 6);
    }
}

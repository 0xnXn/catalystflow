package com.catalystflow.review;

import com.catalystflow.review.domain.ManagerGateStatus;
import com.catalystflow.review.model.*;
import com.catalystflow.review.service.ReviewCycleService;
import com.catalystflow.review.service.ReviewService;
import com.catalystflow.review.service.UserLookup;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Integration tests for ReviewResource.
 *
 * RestAssured makes real HTTP calls to the running Quarkus server.
 * ReviewService and ClaimsContext are mocked so tests run without DynamoDB or Cognito.
 */
@QuarkusTest
class ReviewResourceTest {

    @InjectMock
    ReviewService reviewService;

    @InjectMock
    ClaimsContext claimsContext;

    @InjectMock
    UserLookup userLookup;

    @InjectMock
    ReviewCycleService cycleService;

    @BeforeEach
    void setUp() {
        reset(reviewService);
        reset(userLookup);
        reset(cycleService);
        when(claimsContext.isAdmin()).thenReturn(false);
        when(claimsContext.isManager()).thenReturn(false);
        when(claimsContext.getUserId()).thenReturn("");
        when(reviewService.deriveOverallStatus(any())).thenReturn("NOT_STARTED");
        // Default: subject "alice" reports to "manager-id". Lets manager tests pass the
        // isSubjectsManager() check on /reviews POST/PUT for MANAGER reviews.
        when(userLookup.get("alice")).thenReturn(Optional.of(
                new UserLookup.UserSummary("alice", "Active", "EMPLOYEE", "manager-id")));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void asAdmin() {
        when(claimsContext.isAdmin()).thenReturn(true);
        when(claimsContext.isManager()).thenReturn(false);
        when(claimsContext.getUserId()).thenReturn("admin-id");
    }

    private void asManager() {
        when(claimsContext.isAdmin()).thenReturn(false);
        when(claimsContext.isManager()).thenReturn(true);
        when(claimsContext.getUserId()).thenReturn("manager-id");
    }

    private void asEmployee(String userId) {
        when(claimsContext.isAdmin()).thenReturn(false);
        when(claimsContext.isManager()).thenReturn(false);
        when(claimsContext.getUserId()).thenReturn(userId);
    }

    private Review sampleReview(String reviewId, String authorUserId) {
        Review r = new Review();
        r.setReviewId(reviewId);
        r.setCycleId("cycle-1");
        r.setTemplateId("tmpl-1");
        r.setSubjectUserId("alice");
        r.setAuthorUserId(authorUserId);
        r.setReviewType(Review.ReviewTypeEnum.SELF);
        r.setStatus(Review.StatusEnum.NOT_STARTED);
        r.setVersion(1);
        return r;
    }

    private Review sampleManagerReview(String reviewId) {
        Review r = sampleReview(reviewId, "manager-id");
        r.setReviewType(Review.ReviewTypeEnum.MANAGER);
        r.setStatus(Review.StatusEnum.DRAFT);
        return r;
    }

    private ManagerGateStatus openGate() {
        return new ManagerGateStatus(
                true,
                "cycle-1",
                "alice",
                new ManagerGateStatus.SelfReviewSummary("COMPLETED", "self-1"),
                new ManagerGateStatus.Peer360Summary(false, 0, 0, List.of()),
                List.of()
        );
    }

    private ManagerGateStatus closedGate() {
        return new ManagerGateStatus(
                false,
                "cycle-1",
                "alice",
                new ManagerGateStatus.SelfReviewSummary("DRAFT", "self-1"),
                new ManagerGateStatus.Peer360Summary(true, 1, 0, List.of("peer-a")),
                List.of(
                        ManagerGateStatus.GateReason.selfIncomplete("DRAFT"),
                        ManagerGateStatus.GateReason.peer360Incomplete(1)
                )
        );
    }

    // ── Health ────────────────────────────────────────────────────────────────

    @Test
    void health_Returns200() {
        given()
            .when().get("/reviews/health")
            .then()
            .statusCode(200)
            .body("message", containsString("Review Service is Online"));
    }

    // ── GET /reviews (search) ─────────────────────────────────────────────────

    @Test
    void search_BySubject_Admin_Returns200() {
        asAdmin();
        when(reviewService.getReviewsForSubject("alice"))
                .thenReturn(List.of(sampleReview("r1", "alice")));

        given()
            .queryParam("subjectUserId", "alice")
            .when().get("/reviews")
            .then()
            .statusCode(200)
            .body("reviews.size()", equalTo(1))
            .body("reviews[0].reviewId", equalTo("r1"));
    }

    @Test
    void search_ByCycle_Manager_Returns200() {
        asManager();
        when(reviewService.getReviewsForCycle("cycle-1"))
                .thenReturn(List.of(sampleReview("r1", "alice")));

        given()
            .queryParam("cycleId", "cycle-1")
            .when().get("/reviews")
            .then()
            .statusCode(200)
            .body("reviews.size()", equalTo(1));
    }

    // ── answers projection: subject-keyed detail keeps answers, cycle-keyed
    //    overview strips them. The manager review form + cycle-summary read
    //    answers off the subject query; dashboard/insights only need status. ──

    private Review sampleReviewWithAnswers(String reviewId, String authorUserId) {
        Review r = sampleReview(reviewId, authorUserId);
        ReviewAnswer a = new ReviewAnswer();
        a.setQuestionId("q1");
        a.setResponse("A detailed self-review answer about my contributions this cycle.");
        r.setAnswers(List.of(a));
        return r;
    }

    @Test
    void search_BySubject_includesAnswers() {
        asManager();
        when(reviewService.getReviewsForSubject("alice"))
                .thenReturn(List.of(sampleReviewWithAnswers("r1", "alice")));

        given()
            .queryParam("subjectUserId", "alice")
            .when().get("/reviews")
            .then()
            .statusCode(200)
            .body("reviews[0].answers.size()", equalTo(1))
            .body("reviews[0].answers[0].questionId", equalTo("q1"));
    }

    @Test
    void search_ByCycle_stripsAnswers() {
        asManager();
        when(reviewService.getReviewsForCycle("cycle-1"))
                .thenReturn(List.of(sampleReviewWithAnswers("r1", "alice")));

        given()
            .queryParam("cycleId", "cycle-1")
            .when().get("/reviews")
            .then()
            .statusCode(200)
            .body("reviews[0].answers", nullValue());
    }

    @Test
    void search_NoParams_Returns400() {
        asAdmin();

        given()
            .when().get("/reviews")
            .then()
            .statusCode(400);
    }

    @Test
    void search_Employee_Returns403() {
        asEmployee("alice");

        given()
            .queryParam("subjectUserId", "alice")
            .when().get("/reviews")
            .then()
            .statusCode(403);
    }

    @Test
    void search_BySubject_ManagerNotSubjectsManager_Returns403() {
        // PRE-7: a manager may only pull reviews for their own reports. "carol"
        // reports to someone else, so this manager can't read her full reviews.
        asManager(); // manager-id
        when(userLookup.get("carol")).thenReturn(Optional.of(
                new UserLookup.UserSummary("carol", "Active", "EMPLOYEE", "other-mgr")));

        given()
            .queryParam("subjectUserId", "carol")
            .when().get("/reviews")
            .then()
            .statusCode(403);

        verify(reviewService, never()).getReviewsForSubject("carol");
    }

    // ── GET /reviews/my-review ─────────────────────────────────────────────────────

    @Test
    void mine_AuthenticatedEmployee_Returns200() {
        asEmployee("alice");
        when(reviewService.getReviewsForSubject("alice"))
                .thenReturn(List.of(sampleReview("r1", "alice")));

        given()
            .when().get("/reviews/my-review")
            .then()
            .statusCode(200)
            .body("reviews.size()", equalTo(1));
    }

    @Test
    void mine_Unauthenticated_Returns401() {
        given()
            .when().get("/reviews/my-review")
            .then()
            .statusCode(401);
    }

    @Test
    void myReview_includesAnswers_soPublishedManagerFeedbackPopulates() {
        // Regression: /reviews/my-review must return answer content. The employee
        // reads their PUBLISHED manager review's feedback here; a prior strip made
        // it show up empty in employee accounts.
        asEmployee("alice");
        Review mgr = sampleReviewWithAnswers("mgr-1", "manager-id");
        mgr.setReviewType(Review.ReviewTypeEnum.MANAGER);
        mgr.setStatus(Review.StatusEnum.PUBLISHED);
        when(reviewService.getReviewsForSubject("alice")).thenReturn(List.of(mgr));

        given()
            .when().get("/reviews/my-review")
            .then()
            .statusCode(200)
            .body("reviews[0].reviewType", equalTo("MANAGER"))
            .body("reviews[0].answers.size()", equalTo(1))
            .body("reviews[0].answers[0].questionId", equalTo("q1"));
    }

    // ── GET /reviews/{id} ─────────────────────────────────────────────────────

    @Test
    void getById_Found_Returns200() {
        asEmployee("alice");
        when(reviewService.getReviewById("r1"))
                .thenReturn(Optional.of(sampleReview("r1", "alice")));

        given()
            .when().get("/reviews/r1")
            .then()
            .statusCode(200)
            .body("reviewId",   equalTo("r1"))
            .body("reviewType", equalTo("SELF"))
            .body("status",     equalTo("NOT_STARTED"));
    }

    @Test
    void getById_NotFound_Returns404() {
        asEmployee("alice");
        when(reviewService.getReviewById("missing")).thenReturn(Optional.empty());

        given()
            .when().get("/reviews/missing")
            .then()
            .statusCode(404);
    }

    @Test
    void getById_UnrelatedEmployee_Returns404() {
        // PRE-6: previously ANY authenticated user could read ANY review by id.
        // An employee who is neither author, subject, nor the subject's manager
        // now gets 404 — not 403 — so a stranger can't probe which ids exist.
        asEmployee("carol");
        when(reviewService.getReviewById("r1"))
                .thenReturn(Optional.of(sampleReview("r1", "alice"))); // subject=alice, author=alice

        given()
            .when().get("/reviews/r1")
            .then()
            .statusCode(404);
    }

    @Test
    void getById_SubjectsManager_Returns200() {
        // alice reports to manager-id (setUp) — her manager may read her review.
        asManager();
        when(reviewService.getReviewById("r1"))
                .thenReturn(Optional.of(sampleReview("r1", "alice")));

        given()
            .when().get("/reviews/r1")
            .then()
            .statusCode(200)
            .body("reviewId", equalTo("r1"));
    }

    @Test
    void getById_Admin_Returns200() {
        asAdmin();
        when(reviewService.getReviewById("r1"))
                .thenReturn(Optional.of(sampleReview("r1", "alice")));

        given()
            .when().get("/reviews/r1")
            .then()
            .statusCode(200);
    }

    // ── POST /reviews ─────────────────────────────────────────────────────────

    @Test
    void create_Admin_Returns201() {
        asAdmin();
        Review created = sampleReview("new-id", "alice");
        when(reviewService.create(any(CreateReviewRequest.class), eq("admin-id")))
                .thenReturn(created);

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "cycleId",       "cycle-1",
                "templateId",    "tmpl-1",
                "subjectUserId", "alice",
                "authorUserId",  "alice",
                "reviewType",    "SELF"
            ))
            .when().post("/reviews")
            .then()
            .statusCode(201)
            .body("reviewId", equalTo("new-id"));
    }

    @Test
    void create_Employee_OwnSelfReview_Returns201() {
        asEmployee("alice");
        Review created = sampleReview("new-id", "alice");
        when(reviewService.create(any(CreateReviewRequest.class), eq("alice")))
                .thenReturn(created);

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "cycleId",       "cycle-1",
                "templateId",    "tmpl-1",
                "subjectUserId", "alice",   // same as caller
                "authorUserId",  "alice",
                "reviewType",    "SELF"
            ))
            .when().post("/reviews")
            .then()
            .statusCode(201)
            .body("reviewId", equalTo("new-id"));
    }

    @Test
    void create_Employee_OtherSubject_Returns403() {
        // Employee trying to create a review for someone else — forbidden
        asEmployee("alice");

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "cycleId",       "cycle-1",
                "templateId",    "tmpl-1",
                "subjectUserId", "bob",     // different user
                "authorUserId",  "alice",
                "reviewType",    "SELF"
            ))
            .when().post("/reviews")
            .then()
            .statusCode(403);

        verify(reviewService, never()).create(any(), any());
    }

    @Test
    void create_Employee_ManagerReviewType_Returns403() {
        // Employee cannot create a MANAGER type review
        asEmployee("alice");

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "cycleId",       "cycle-1",
                "templateId",    "tmpl-1",
                "subjectUserId", "alice",
                "authorUserId",  "alice",
                "reviewType",    "MANAGER"
            ))
            .when().post("/reviews")
            .then()
            .statusCode(403);

        verify(reviewService, never()).create(any(), any());
    }

    @Test
    void create_Manager_Returns201() {
        asManager();
        Review created = sampleReview("new-id", "manager-id");
        created.setReviewType(Review.ReviewTypeEnum.MANAGER);
        when(reviewService.create(any(CreateReviewRequest.class), eq("manager-id")))
                .thenReturn(created);

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "cycleId",       "cycle-1",
                "templateId",    "tmpl-1",
                "subjectUserId", "alice",
                "authorUserId",  "manager-id",
                "reviewType",    "MANAGER"
            ))
            .when().post("/reviews")
            .then()
            .statusCode(201);
    }

    @Test
    void createManagerReview_allowedEvenWhenGateClosed() {
        // Gate is advisory now — a manager may create their review even when the
        // employee's self-review / assigned peers are outstanding (e.g. beating a
        // deadline). The gate is no longer consulted on the create path.
        asManager();
        Review created = sampleReview("new-id", "manager-id");
        created.setReviewType(Review.ReviewTypeEnum.MANAGER);
        when(reviewService.create(any(CreateReviewRequest.class), eq("manager-id")))
                .thenReturn(created);

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "cycleId",       "cycle-1",
                "templateId",    "tmpl-1",
                "subjectUserId", "alice",
                "authorUserId",  "manager-id",
                "reviewType",    "MANAGER"
            ))
            .when().post("/reviews")
            .then()
            .statusCode(201)
            .body("reviewId", equalTo("new-id"));

        verify(reviewService, never()).getManagerGateStatus(any(), any());
        verify(reviewService, times(1)).create(any(), eq("manager-id"));
    }

    @Test
    void adminCanBypassGate_onCreate() {
        asAdmin();
        Review created = sampleManagerReview("new-id");
        when(reviewService.create(any(CreateReviewRequest.class), eq("admin-id")))
                .thenReturn(created);

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "cycleId",       "cycle-1",
                "templateId",    "tmpl-1",
                "subjectUserId", "alice",
                "authorUserId",  "manager-id",
                "reviewType",    "MANAGER"
            ))
            .when().post("/reviews")
            .then()
            .statusCode(201);

        verify(reviewService, never()).getManagerGateStatus(any(), any());
    }

    // ── PUT /reviews/{id} ─────────────────────────────────────────────────────

    @Test
    void update_Author_Returns200() {
        asEmployee("alice");
        Review existing = sampleReview("r1", "alice");
        Review updated  = sampleReview("r1", "alice");
        updated.setStatus(Review.StatusEnum.IN_PROGRESS);
        updated.setVersion(2);

        when(reviewService.getReviewById("r1")).thenReturn(Optional.of(existing));
        when(reviewService.update(eq("r1"), any(UpdateReviewRequest.class))).thenReturn(updated);

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("status", "IN_PROGRESS", "version", 1))
            .when().put("/reviews/r1")
            .then()
            .statusCode(200)
            .body("status",  equalTo("IN_PROGRESS"))
            .body("version", equalTo(2));
    }

    @Test
    void update_NonAuthorEmployee_Returns403() {
        asEmployee("bob");
        when(reviewService.getReviewById("r1"))
                .thenReturn(Optional.of(sampleReview("r1", "alice"))); // alice's review

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("status", "IN_PROGRESS"))
            .when().put("/reviews/r1")
            .then()
            .statusCode(403);
    }

    @Test
    void update_NotFound_Returns404() {
        asAdmin();
        when(reviewService.getReviewById("missing")).thenReturn(Optional.empty());

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("status", "IN_PROGRESS"))
            .when().put("/reviews/missing")
            .then()
            .statusCode(404);
    }

    @Test
    void submitManagerReview_allowedEvenWhenGateClosed() {
        // Advisory gate — manager submit goes through even with outstanding
        // self/peer prerequisites. The gate is no longer consulted on submit.
        asManager();
        Review updated = sampleManagerReview("r1");
        updated.setStatus(Review.StatusEnum.COMPLETED);
        updated.setVersion(2);
        when(reviewService.getReviewById("r1")).thenReturn(Optional.of(sampleManagerReview("r1")));
        when(reviewService.update(eq("r1"), any(UpdateReviewRequest.class))).thenReturn(updated);

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("status", "COMPLETED", "version", 1))
            .when().put("/reviews/r1")
            .then()
            .statusCode(200)
            .body("status", equalTo("COMPLETED"));

        verify(reviewService, never()).getManagerGateStatus(any(), any());
        verify(reviewService, times(1)).update(eq("r1"), any(UpdateReviewRequest.class));
    }

    @Test
    void submitManagerReview_succeeds_whenGateOpen() {
        asManager();
        Review updated = sampleManagerReview("r1");
        updated.setStatus(Review.StatusEnum.COMPLETED);
        updated.setVersion(2);
        when(reviewService.getReviewById("r1")).thenReturn(Optional.of(sampleManagerReview("r1")));
        when(reviewService.update(eq("r1"), any(UpdateReviewRequest.class))).thenReturn(updated);

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("status", "COMPLETED", "version", 1))
            .when().put("/reviews/r1")
            .then()
            .statusCode(200)
            .body("status", equalTo("COMPLETED"));
    }

    @Test
    void adminCanBypassGate_onSubmit() {
        asAdmin();
        Review updated = sampleManagerReview("r1");
        updated.setStatus(Review.StatusEnum.COMPLETED);
        when(reviewService.getReviewById("r1")).thenReturn(Optional.of(sampleManagerReview("r1")));
        when(reviewService.update(eq("r1"), any(UpdateReviewRequest.class))).thenReturn(updated);

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("status", "COMPLETED", "version", 1))
            .when().put("/reviews/r1")
            .then()
            .statusCode(200);

        verify(reviewService, never()).getManagerGateStatus(any(), any());
    }

    @Test
    void getManagerGate_returns403_whenEmployeeCaller() {
        asEmployee("alice");

        given()
            .queryParam("cycleId", "cycle-1")
            .queryParam("subjectUserId", "alice")
            .when().get("/reviews/manager-gate")
            .then()
            .statusCode(403);
    }

    @Test
    void getManagerGate_returnsStatus_whenManagerCaller() {
        asManager();
        when(reviewService.getManagerGateStatus("cycle-1", "alice")).thenReturn(openGate());

        given()
            .queryParam("cycleId", "cycle-1")
            .queryParam("subjectUserId", "alice")
            .when().get("/reviews/manager-gate")
            .then()
            .statusCode(200)
            .body("open", equalTo(true));
    }

    @Test
    void getManagerGate_returnsStatus_whenAdminCaller() {
        asAdmin();
        when(reviewService.getManagerGateStatus("cycle-1", "alice")).thenReturn(closedGate());

        given()
            .queryParam("cycleId", "cycle-1")
            .queryParam("subjectUserId", "alice")
            .when().get("/reviews/manager-gate")
            .then()
            .statusCode(200)
            .body("open", equalTo(false));
    }

    // ── DELETE /reviews/{id} ──────────────────────────────────────────────────

    @Test
    void delete_Admin_Returns204() {
        asAdmin();
        when(reviewService.getReviewById("r1"))
                .thenReturn(Optional.of(sampleReview("r1", "alice")));

        given()
            .when().delete("/reviews/r1")
            .then()
            .statusCode(204);

        verify(reviewService).delete("r1");
    }

    @Test
    void delete_NonAdmin_Returns403() {
        asEmployee("alice");

        given()
            .when().delete("/reviews/r1")
            .then()
            .statusCode(403);

        verify(reviewService, never()).delete(any());
    }

    @Test
    void delete_NotFound_Returns404() {
        asAdmin();
        when(reviewService.getReviewById("missing")).thenReturn(Optional.empty());

        given()
            .when().delete("/reviews/missing")
            .then()
            .statusCode(404);

        verify(reviewService, never()).delete(any());
    }

    // ── DRAFT ─────────────────────────────────────────────────────────────────

    @Test
    void update_SaveAsDraft_Returns200() {
        asEmployee("alice");
        Review existing = sampleReview("r1", "alice");
        Review drafted  = sampleReview("r1", "alice");
        drafted.setStatus(Review.StatusEnum.DRAFT);
        drafted.setVersion(2);

        when(reviewService.getReviewById("r1")).thenReturn(Optional.of(existing));
        when(reviewService.update(eq("r1"), any(UpdateReviewRequest.class))).thenReturn(drafted);

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("status", "DRAFT", "version", 1))
            .when().put("/reviews/r1")
            .then()
            .statusCode(200)
            .body("status",  equalTo("DRAFT"))
            .body("version", equalTo(2));
    }

    // ── Manager-of-subject gate (MANAGER review create / submit) ──────────────

    @Test
    void create_ManagerNotOfSubject_Returns403() {
        asManager(); // callerId = "manager-id"
        // Alice now reports to a *different* manager
        when(userLookup.get("alice")).thenReturn(Optional.of(
                new UserLookup.UserSummary("alice", "Active", "EMPLOYEE", "other-mgr")));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "cycleId",       "cycle-1",
                "templateId",    "tmpl-1",
                "subjectUserId", "alice",
                "authorUserId",  "manager-id",
                "reviewType",    "MANAGER"
            ))
            .when().post("/reviews")
            .then()
            .statusCode(403)
            .body("message", containsString("not the manager"));

        verify(reviewService, never()).create(any(), any());
        verify(reviewService, never()).getManagerGateStatus(any(), any());
    }

    @Test
    void submit_ManagerNotOfSubject_Returns403() {
        asManager(); // callerId = "manager-id"
        when(userLookup.get("alice")).thenReturn(Optional.of(
                new UserLookup.UserSummary("alice", "Active", "EMPLOYEE", "other-mgr")));
        when(reviewService.getReviewById("r1")).thenReturn(Optional.of(sampleManagerReview("r1")));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("status", "COMPLETED", "version", 1))
            .when().put("/reviews/r1")
            .then()
            .statusCode(403)
            .body("message", containsString("not the manager"));

        verify(reviewService, never()).update(any(), any());
        verify(reviewService, never()).getManagerGateStatus(any(), any());
    }

    @Test
    void create_ManagerNotOfSubject_AdminOverride_Returns201() {
        // Admin always bypasses the manager-of-subject check.
        asAdmin();
        when(userLookup.get("alice")).thenReturn(Optional.of(
                new UserLookup.UserSummary("alice", "Active", "EMPLOYEE", "other-mgr")));
        Review created = sampleManagerReview("new-id");
        when(reviewService.create(any(CreateReviewRequest.class), eq("admin-id")))
                .thenReturn(created);

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "cycleId",       "cycle-1",
                "templateId",    "tmpl-1",
                "subjectUserId", "alice",
                "authorUserId",  "manager-id",
                "reviewType",    "MANAGER"
            ))
            .when().post("/reviews")
            .then()
            .statusCode(201);
    }

    // ── PEER_360 anonymity ────────────────────────────────────────────────────

    @Test
    void mine_FiltersOutPeer360Rows() {
        asEmployee("alice");
        Review self = sampleReview("self-1", "alice");
        Review peer = sampleReview("peer-1", "bob");
        peer.setReviewType(Review.ReviewTypeEnum.PEER_360);
        Review mgr = sampleReview("mgr-1", "manager-id");
        mgr.setReviewType(Review.ReviewTypeEnum.MANAGER);

        when(reviewService.getReviewsForSubject("alice"))
                .thenReturn(List.of(self, peer, mgr));

        given()
            .when().get("/reviews/my-review")
            .then()
            .statusCode(200)
            .body("reviews.size()", equalTo(2))
            .body("reviews.reviewType", hasItems("SELF", "MANAGER"))
            .body("reviews.reviewType", not(hasItem("PEER_360")));
    }

    @Test
    void getById_SubjectReadingOwnPeer360_Returns403() {
        asEmployee("alice");
        Review peer = sampleReview("peer-1", "bob");
        peer.setReviewType(Review.ReviewTypeEnum.PEER_360);
        peer.setSubjectUserId("alice");
        when(reviewService.getReviewById("peer-1")).thenReturn(Optional.of(peer));

        given()
            .when().get("/reviews/peer-1")
            .then()
            .statusCode(403)
            .body("message", containsString("not visible"));
    }

    @Test
    void getById_ManagerReadingPeer360_Returns200() {
        asManager();
        Review peer = sampleReview("peer-1", "bob");
        peer.setReviewType(Review.ReviewTypeEnum.PEER_360);
        peer.setSubjectUserId("alice");
        when(reviewService.getReviewById("peer-1")).thenReturn(Optional.of(peer));

        given()
            .when().get("/reviews/peer-1")
            .then()
            .statusCode(200)
            .body("reviewType", equalTo("PEER_360"));
    }

    @Test
    void getById_AuthorReadingTheirOwnPeer360Draft_Returns200() {
        // Bob is writing the PEER_360 about Alice — he must be able to read his own row.
        asEmployee("bob");
        Review peer = sampleReview("peer-1", "bob");
        peer.setReviewType(Review.ReviewTypeEnum.PEER_360);
        peer.setSubjectUserId("alice");
        when(reviewService.getReviewById("peer-1")).thenReturn(Optional.of(peer));

        given()
            .when().get("/reviews/peer-1")
            .then()
            .statusCode(200);
    }

    // ── PEER_360 fill gate — only ACTIVE cycles surface in /assigned-to-me ────

    @Test
    void assignedToMe_FiltersPeer360_WhenCycleScheduled() {
        asEmployee("bob");
        Review peer = sampleReview("peer-1", "bob");
        peer.setReviewType(Review.ReviewTypeEnum.PEER_360);
        peer.setCycleId("cycle-scheduled");

        ReviewCycle scheduled = new ReviewCycle();
        scheduled.setCycleId("cycle-scheduled");
        scheduled.setStatus(ReviewCycle.StatusEnum.SCHEDULED);

        when(reviewService.getReviewsForAuthor("bob")).thenReturn(List.of(peer));
        when(cycleService.getReviewCycle("cycle-scheduled")).thenReturn(Optional.of(scheduled));

        given()
            .when().get("/reviews/assigned-to-me")
            .then()
            .statusCode(200)
            .body("reviews.size()", equalTo(0));
    }

    @Test
    void assignedToMe_SurfacesPeer360_WhenCycleActive() {
        asEmployee("bob");
        Review peer = sampleReview("peer-1", "bob");
        peer.setReviewType(Review.ReviewTypeEnum.PEER_360);
        peer.setCycleId("cycle-active");

        ReviewCycle active = new ReviewCycle();
        active.setCycleId("cycle-active");
        active.setStatus(ReviewCycle.StatusEnum.ACTIVE);

        when(reviewService.getReviewsForAuthor("bob")).thenReturn(List.of(peer));
        when(cycleService.getReviewCycle("cycle-active")).thenReturn(Optional.of(active));

        given()
            .when().get("/reviews/assigned-to-me")
            .then()
            .statusCode(200)
            .body("reviews.size()", equalTo(1))
            .body("reviews[0].reviewType", equalTo("PEER_360"));
    }

    @Test
    void assignedToMe_AlwaysSurfacesSelfAndManagerRows() {
        asEmployee("alice");
        Review self = sampleReview("self-1", "alice");
        Review mgr = sampleReview("mgr-1", "alice");
        mgr.setReviewType(Review.ReviewTypeEnum.MANAGER);

        when(reviewService.getReviewsForAuthor("alice"))
                .thenReturn(List.of(self, mgr));

        given()
            .when().get("/reviews/assigned-to-me")
            .then()
            .statusCode(200)
            .body("reviews.size()", equalTo(2));

        // SELF / MANAGER fillable regardless of cycle status — never call cycleService.
        verify(cycleService, never()).getReviewCycle(any());
    }

    // ── CORS ──────────────────────────────────────────────────────────────────

    @Test
    void allResponses_HaveCorsHeader() {
        given()
            .when().get("/reviews/health")
            .then()
            .header("Access-Control-Allow-Origin", "*");
    }
}

package com.catalystflow.review;

import com.catalystflow.review.model.CreatePeer360AssignmentsRequest;
import com.catalystflow.review.model.Peer360AssignmentResult;
import com.catalystflow.review.model.Review;
import com.catalystflow.review.model.ReviewAnswer;
import com.catalystflow.review.service.Peer360AssignmentService;
import com.catalystflow.review.service.Peer360AssignmentService.DeleteOutcome;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@QuarkusTest
class Peer360AssignmentResourceTest {

    @InjectMock
    Peer360AssignmentService assignmentService;

    @InjectMock
    ClaimsContext claimsContext;

    @InjectMock
    UserLookup userLookup;

    @BeforeEach
    void setUp() {
        reset(assignmentService, userLookup);
        when(claimsContext.isAdmin()).thenReturn(false);
        when(claimsContext.isManager()).thenReturn(false);
        when(claimsContext.getUserId()).thenReturn("");
        // Default world: subject "alice" reports to "manager-id" (the asManager()
        // caller), so subject-manager authz passes for the positive manager tests.
        // Negative tests override via subjectReportsTo(...).
        subjectReportsTo("alice", "manager-id");
    }

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

    private Peer360AssignmentResult sampleResult() {
        Peer360AssignmentResult r = new Peer360AssignmentResult();
        Review row = new Review();
        row.setReviewId("rev-bob");
        row.setCycleId("cycle-1");
        row.setSubjectUserId("alice");
        row.setAuthorUserId("bob");
        row.setReviewType(Review.ReviewTypeEnum.PEER_360);
        row.setStatus(Review.StatusEnum.NOT_STARTED);
        r.setCreated(List.of(row));
        r.setSkipped(List.of());
        r.setAll(List.of(row));
        return r;
    }

    private Review samplePeer360(String reviewId, String authorUserId, Review.StatusEnum status) {
        Review r = new Review();
        r.setReviewId(reviewId);
        r.setCycleId("cycle-1");
        r.setSubjectUserId("alice");
        r.setAuthorUserId(authorUserId);
        r.setReviewType(Review.ReviewTypeEnum.PEER_360);
        r.setStatus(status);
        return r;
    }

    // ── 1 ────────────────────────────────────────────────────────────────────
    @Test
    void create_returns403_forEmployeeCaller() {
        asEmployee("alice");
        Map<String, Object> body = Map.of(
                "subjectUserId", "alice",
                "authorUserIds", List.of("bob"),
                "templateId", "tpl-1");

        given()
                .contentType(ContentType.JSON).body(body)
                .when().post("/cycles/cycle-1/peer360-assignments")
                .then().statusCode(403);

        verify(assignmentService, never()).createAssignments(anyString(), any(), anyString());
    }

    // ── 2 ────────────────────────────────────────────────────────────────────
    @Test
    void create_returns200_forManagerCaller() {
        asManager();
        when(assignmentService.createAssignments(eq("cycle-1"), any(), eq("manager-id")))
                .thenReturn(sampleResult());

        Map<String, Object> body = Map.of(
                "subjectUserId", "alice",
                "authorUserIds", List.of("bob"),
                "templateId", "tpl-1");

        given()
                .contentType(ContentType.JSON).body(body)
                .when().post("/cycles/cycle-1/peer360-assignments")
                .then()
                .statusCode(200)
                .body("created.size()", is(1))
                .body("created[0].authorUserId", equalTo("bob"))
                .body("created[0].reviewType", equalTo("PEER_360"));
    }

    // ── 3 ────────────────────────────────────────────────────────────────────
    @Test
    void create_returns200_forAdminCaller() {
        asAdmin();
        when(assignmentService.createAssignments(eq("cycle-1"), any(), eq("admin-id")))
                .thenReturn(sampleResult());

        Map<String, Object> body = Map.of(
                "subjectUserId", "alice",
                "authorUserIds", List.of("bob"),
                "templateId", "tpl-1");

        given()
                .contentType(ContentType.JSON).body(body)
                .when().post("/cycles/cycle-1/peer360-assignments")
                .then().statusCode(200);
    }

    // ── 4 ────────────────────────────────────────────────────────────────────
    @Test
    void list_returns403_forSubjectThemselves() {
        asEmployee("alice");

        given()
                .when().get("/cycles/cycle-1/peer360-assignments?subjectUserId=alice")
                .then().statusCode(403);

        verify(assignmentService, never()).listAssignments(anyString(), anyString());
    }

    // ── 5 ────────────────────────────────────────────────────────────────────
    @Test
    void list_returns403_forUnrelatedEmployee() {
        asEmployee("eve");

        given()
                .when().get("/cycles/cycle-1/peer360-assignments?subjectUserId=alice")
                .then().statusCode(403);
    }

    // ── 6 ────────────────────────────────────────────────────────────────────
    @Test
    void list_returns200_forManagerCaller() {
        asManager();
        when(assignmentService.listAssignments("cycle-1", "alice"))
                .thenReturn(List.of(samplePeer360("rev-bob", "bob", Review.StatusEnum.NOT_STARTED)));

        given()
                .when().get("/cycles/cycle-1/peer360-assignments?subjectUserId=alice")
                .then()
                .statusCode(200)
                .body("size()", is(1))
                .body("[0].authorUserId", equalTo("bob"));
    }

    // ── 6b ───────────────────────────────────────────────────────────────────
    @Test
    void list_includesAnswers_forManagerRawTab() {
        // Regression: the manager review form's "360 Raw" tab renders
        // peer.answers and uses them for citation highlighting. This
        // admin/manager-only endpoint must return answer content; a prior
        // strip emptied the manager's peer-feedback view.
        asManager();
        Review peer = samplePeer360("rev-bob", "bob", Review.StatusEnum.COMPLETED);
        ReviewAnswer a = new ReviewAnswer();
        a.setQuestionId("q1");
        a.setResponse("Bob collaborates well and mentors juniors.");
        peer.setAnswers(List.of(a));
        when(assignmentService.listAssignments("cycle-1", "alice"))
                .thenReturn(List.of(peer));

        given()
                .when().get("/cycles/cycle-1/peer360-assignments?subjectUserId=alice")
                .then()
                .statusCode(200)
                .body("[0].answers.size()", is(1))
                .body("[0].answers[0].questionId", equalTo("q1"));
    }

    // ── 7 ────────────────────────────────────────────────────────────────────
    @Test
    void delete_returns403_forEmployeeCaller() {
        asEmployee("alice");

        given()
                .when().delete("/cycles/cycle-1/peer360-assignments/rev-bob")
                .then().statusCode(403);

        verify(assignmentService, never()).deleteAssignment(anyString());
    }

    // ── 8 ────────────────────────────────────────────────────────────────────
    @Test
    void delete_returns204_forManagerOnNotStarted() {
        asManager();
        Review row = samplePeer360("rev-bob", "bob", Review.StatusEnum.NOT_STARTED);
        when(assignmentService.getAssignment("rev-bob")).thenReturn(Optional.of(row));
        when(assignmentService.deleteAssignment("rev-bob")).thenReturn(DeleteOutcome.DELETED);

        given()
                .when().delete("/cycles/cycle-1/peer360-assignments/rev-bob")
                .then().statusCode(204);
    }

    // ── 9 ────────────────────────────────────────────────────────────────────
    @Test
    void delete_returns409_forManagerOnInProgress() {
        asManager();
        Review row = samplePeer360("rev-bob", "bob", Review.StatusEnum.IN_PROGRESS);
        when(assignmentService.getAssignment("rev-bob")).thenReturn(Optional.of(row));
        when(assignmentService.deleteAssignment("rev-bob")).thenReturn(DeleteOutcome.IN_PROGRESS);

        given()
                .when().delete("/cycles/cycle-1/peer360-assignments/rev-bob")
                .then()
                .statusCode(409)
                .body("message", containsString("started"));
    }

    // ── 10 ───────────────────────────────────────────────────────────────────
    @Test
    void delete_returns404_whenAssignmentMissing() {
        asManager();
        when(assignmentService.getAssignment("ghost")).thenReturn(Optional.empty());

        given()
                .when().delete("/cycles/cycle-1/peer360-assignments/ghost")
                .then().statusCode(404);

        verify(assignmentService, never()).deleteAssignment(anyString());
    }

    // ── 11 ───────────────────────────────────────────────────────────────────
    @Test
    void delete_returns404_whenAssignmentBelongsToDifferentCycle() {
        asManager();
        Review row = samplePeer360("rev-bob", "bob", Review.StatusEnum.NOT_STARTED);
        // row is in cycle-1, caller hits cycle-OTHER
        when(assignmentService.getAssignment("rev-bob")).thenReturn(Optional.of(row));

        given()
                .when().delete("/cycles/cycle-OTHER/peer360-assignments/rev-bob")
                .then().statusCode(404);

        verify(assignmentService, never()).deleteAssignment(anyString());
    }

    // ── Subject-manager authorization (RVT-04) ────────────────────────────────

    private void subjectReportsTo(String subjectUserId, String managerId) {
        when(userLookup.get(subjectUserId)).thenReturn(Optional.of(
                new UserLookup.UserSummary(subjectUserId, "Active", "EMPLOYEE", managerId)));
    }

    @Test
    void create_ManagerNotOfSubject_Returns403() {
        asManager(); // caller manager-id
        subjectReportsTo("alice", "other-mgr"); // alice does NOT report to manager-id
        Map<String, Object> body = Map.of(
                "subjectUserId", "alice",
                "authorUserIds", List.of("bob"),
                "templateId", "tpl-1");

        given()
                .contentType(ContentType.JSON).body(body)
                .when().post("/cycles/cycle-1/peer360-assignments")
                .then()
                .statusCode(403)
                .body("message", containsString("not the manager"));

        verify(assignmentService, never()).createAssignments(anyString(), any(), anyString());
    }

    @Test
    void list_ManagerNotOfSubject_Returns403() {
        asManager();
        subjectReportsTo("alice", "other-mgr");

        given()
                .when().get("/cycles/cycle-1/peer360-assignments?subjectUserId=alice")
                .then()
                .statusCode(403)
                .body("message", containsString("not the manager"));

        verify(assignmentService, never()).listAssignments(anyString(), anyString());
    }

    @Test
    void delete_ManagerNotOfSubject_Returns403() {
        asManager();
        Review row = samplePeer360("rev-bob", "bob", Review.StatusEnum.NOT_STARTED); // subject alice, cycle-1
        when(assignmentService.getAssignment("rev-bob")).thenReturn(Optional.of(row));
        subjectReportsTo("alice", "other-mgr");

        given()
                .when().delete("/cycles/cycle-1/peer360-assignments/rev-bob")
                .then()
                .statusCode(403)
                .body("message", containsString("not the manager"));

        verify(assignmentService, never()).deleteAssignment(anyString());
    }

    // ── Review patches: malformed body + admin-bypass coverage ────────────────

    @Test
    void create_MissingSubject_Returns400() {
        asManager(); // would-be authorized, but the body omits subjectUserId
        Map<String, Object> body = Map.of(
                "authorUserIds", List.of("bob"),
                "templateId", "tpl-1");

        given()
                .contentType(ContentType.JSON).body(body)
                .when().post("/cycles/cycle-1/peer360-assignments")
                .then()
                .statusCode(400)
                .body("message", containsString("subjectUserId"));

        verify(assignmentService, never()).createAssignments(anyString(), any(), anyString());
    }

    @Test
    void list_returns200_forAdminCaller() {
        asAdmin();
        when(assignmentService.listAssignments("cycle-1", "alice"))
                .thenReturn(List.of(samplePeer360("rev-bob", "bob", Review.StatusEnum.NOT_STARTED)));

        given()
                .when().get("/cycles/cycle-1/peer360-assignments?subjectUserId=alice")
                .then()
                .statusCode(200)
                .body("size()", is(1));
    }

    @Test
    void delete_returns204_forAdminCaller() {
        asAdmin();
        Review row = samplePeer360("rev-bob", "bob", Review.StatusEnum.NOT_STARTED);
        when(assignmentService.getAssignment("rev-bob")).thenReturn(Optional.of(row));
        when(assignmentService.deleteAssignment("rev-bob")).thenReturn(DeleteOutcome.DELETED);

        given()
                .when().delete("/cycles/cycle-1/peer360-assignments/rev-bob")
                .then().statusCode(204);
    }
}

package com.catalystflow.review;

import com.catalystflow.review.domain.ManagerGateStatus;
import com.catalystflow.review.model.CreateReviewRequest;
import com.catalystflow.review.model.Milestone;
import com.catalystflow.review.model.Review;
import com.catalystflow.review.model.ReviewAnswer;
import com.catalystflow.review.model.ReviewCycle;
import com.catalystflow.review.model.TemplateSnapshot;
import com.catalystflow.review.model.UpdateReviewRequest;
import com.catalystflow.review.service.ReviewCycleService;
import com.catalystflow.review.service.ReviewService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class ReviewServiceTest {

    @Inject
    ReviewService reviewService;

    @InjectMock
    DynamoDbClient mockDynamoDb;

    @InjectMock
    ReviewCycleService mockCycleService;

    private static final String NOW = "2026-01-01T00:00:00Z";

    @Test
    void testGetReviewById_Found() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(buildItem("r1")).build());

        Optional<Review> result = reviewService.getReviewById("r1");

        assertTrue(result.isPresent());
        assertEquals("r1", result.get().getReviewId());
        assertEquals("cycle-1", result.get().getCycleId());
        assertEquals(Review.ReviewTypeEnum.SELF, result.get().getReviewType());
        assertEquals(Review.StatusEnum.NOT_STARTED, result.get().getStatus());
    }

    @Test
    void testGetReviewById_NotFound_ReturnsEmpty() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        assertTrue(reviewService.getReviewById("missing").isEmpty());
    }

    @Test
    void testCreate_SavesToDynamoDB() {
        when(mockDynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(PutItemResponse.builder().build());

        CreateReviewRequest req = new CreateReviewRequest();
        req.setCycleId("cycle-1");
        req.setTemplateId("tmpl-1");
        req.setSubjectUserId("alice");
        req.setAuthorUserId("alice");
        req.setReviewType(CreateReviewRequest.ReviewTypeEnum.SELF);

        Review created = reviewService.create(req, "admin");

        assertNotNull(created.getReviewId());
        assertEquals("cycle-1", created.getCycleId());
        assertEquals("alice", created.getSubjectUserId());
        assertEquals(Review.ReviewTypeEnum.SELF, created.getReviewType());
        assertEquals(Review.StatusEnum.NOT_STARTED, created.getStatus());
        assertEquals(1, created.getVersion());
        verify(mockDynamoDb).putItem(any(PutItemRequest.class));
    }

    @Test
    void testCreate_MissingCycleId_Throws400() {
        CreateReviewRequest req = new CreateReviewRequest();
        req.setTemplateId("tmpl-1");
        req.setSubjectUserId("alice");
        req.setAuthorUserId("alice");
        req.setReviewType(CreateReviewRequest.ReviewTypeEnum.SELF);

        assertThrows(BadRequestException.class, () -> reviewService.create(req, "admin"));
    }

    @Test
    void testUpdate_StatusTransition_NotStartedToDraft() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(buildItem("r1")).build());
        when(mockDynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(PutItemResponse.builder().build());

        UpdateReviewRequest req = new UpdateReviewRequest();
        req.setStatus(UpdateReviewRequest.StatusEnum.DRAFT);

        Review updated = reviewService.update("r1", req);

        assertEquals(Review.StatusEnum.DRAFT, updated.getStatus());
        assertEquals(2, updated.getVersion());
    }

    @Test
    void testUpdate_StatusTransition_NotStartedToInProgress() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(buildItem("r1")).build());
        when(mockDynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(PutItemResponse.builder().build());

        UpdateReviewRequest req = new UpdateReviewRequest();
        req.setStatus(UpdateReviewRequest.StatusEnum.IN_PROGRESS);

        Review updated = reviewService.update("r1", req);

        assertEquals(Review.StatusEnum.IN_PROGRESS, updated.getStatus());
        assertEquals(2, updated.getVersion());
        verify(mockDynamoDb).putItem(any(PutItemRequest.class));
    }

    @Test
    void testUpdate_IllegalTransition_Throws400() {
        Map<String, AttributeValue> item = buildItem("r1");
        item.put("status", AttributeValue.builder().s("COMPLETED").build());
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());

        UpdateReviewRequest req = new UpdateReviewRequest();
        req.setStatus(UpdateReviewRequest.StatusEnum.IN_PROGRESS);

        assertThrows(BadRequestException.class, () -> reviewService.update("r1", req));
    }

    @Test
    void testUpdate_ReviewNotFound_Throws404() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        UpdateReviewRequest req = new UpdateReviewRequest();
        req.setStatus(UpdateReviewRequest.StatusEnum.IN_PROGRESS);

        assertThrows(NotFoundException.class, () -> reviewService.update("missing", req));
    }

    /**
     * Regression: partial updates (e.g. persisting only insightsJobId after submission)
     * must not wipe saved answers. UpdateReviewRequest defaults answers to new ArrayList<>()
     * so getAnswers() returns [] (not null) when the field is absent from the JSON payload.
     * The fix guards against empty-list overwrites in ReviewService.update().
     */
    @Test
    void testUpdate_PartialUpdate_DoesNotWipeExistingAnswers() {
        // Existing review has COMPLETED status and one saved answer
        Map<String, AttributeValue> item = buildItem("r1");
        item.put("status", AttributeValue.builder().s("COMPLETED").build());
        ReviewAnswer existingAnswer = new ReviewAnswer();
        existingAnswer.setQuestionId("q1");
        existingAnswer.setResponse("My answer");
        // Store the answer as JSON in the DynamoDB item
        item.put("answers", AttributeValue.builder()
                .s("[{\"questionId\":\"q1\",\"response\":\"My answer\"}]").build());

        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());
        when(mockDynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(PutItemResponse.builder().build());

        // Partial update — only sets insightsJobId, no answers field in payload.
        // UpdateReviewRequest.answers defaults to new ArrayList<>() (not null),
        // so without the isEmpty() guard this would wipe answers.
        UpdateReviewRequest req = new UpdateReviewRequest();
        req.setInsightsJobId("job-abc");

        Review updated = reviewService.update("r1", req);

        assertNotNull(updated.getAnswers());
        assertEquals(1, updated.getAnswers().size(), "Partial update must not wipe saved answers");
        assertEquals("q1", updated.getAnswers().get(0).getQuestionId());
        assertEquals("My answer", updated.getAnswers().get(0).getResponse());
        assertEquals("job-abc", updated.getInsightsJobId());
    }

    @Test
    void testDelete_CallsDynamoDB() {
        when(mockDynamoDb.deleteItem(any(DeleteItemRequest.class)))
                .thenReturn(DeleteItemResponse.builder().build());

        reviewService.delete("r1");

        verify(mockDynamoDb).deleteItem(any(DeleteItemRequest.class));
    }

    @Test
    void gate_open_whenSelfCompletedAndNoPeer360() {
        when(mockDynamoDb.query(any(QueryRequest.class))).thenReturn(
                query(reviewItem("self-1", "SELF", "COMPLETED", "alice", "2026-01-01T00:00:00Z")),
                query()
        );

        ManagerGateStatus gate = reviewService.getManagerGateStatus("cycle-1", "alice");

        assertTrue(gate.open());
        assertEquals(0, gate.peer360().totalAssigned());
        assertTrue(gate.reasons().isEmpty());
    }

    @Test
    void gate_open_whenSelfCompletedAndAllPeer360Completed() {
        when(mockDynamoDb.query(any(QueryRequest.class))).thenReturn(
                query(reviewItem("self-1", "SELF", "COMPLETED", "alice", "2026-01-01T00:00:00Z")),
                query(
                        reviewItem("peer-1", "PEER_360", "COMPLETED", "peer-a", "2026-01-01T00:00:00Z"),
                        reviewItem("peer-2", "PEER_360", "COMPLETED", "peer-b", "2026-01-01T00:00:00Z")
                )
        );

        ManagerGateStatus gate = reviewService.getManagerGateStatus("cycle-1", "alice");

        assertTrue(gate.open());
        assertEquals(2, gate.peer360().completedCount());
        assertTrue(gate.reasons().isEmpty());
    }

    @Test
    void gate_closed_whenSelfMissing() {
        when(mockDynamoDb.query(any(QueryRequest.class))).thenReturn(query(), query());

        ManagerGateStatus gate = reviewService.getManagerGateStatus("cycle-1", "alice");

        assertTrue(!gate.open());
        assertEquals("self_missing", gate.reasons().getFirst().type());
    }

    @Test
    void gate_closed_whenSelfDraft() {
        when(mockDynamoDb.query(any(QueryRequest.class))).thenReturn(
                query(reviewItem("self-1", "SELF", "DRAFT", "alice", "2026-01-01T00:00:00Z")),
                query()
        );

        ManagerGateStatus gate = reviewService.getManagerGateStatus("cycle-1", "alice");

        assertTrue(!gate.open());
        assertEquals("self_incomplete", gate.reasons().getFirst().type());
        assertEquals("DRAFT", gate.reasons().getFirst().currentStatus());
    }

    @Test
    void gate_closed_whenOnePeerStillDraft() {
        when(mockDynamoDb.query(any(QueryRequest.class))).thenReturn(
                query(reviewItem("self-1", "SELF", "COMPLETED", "alice", "2026-01-01T00:00:00Z")),
                query(
                        reviewItem("peer-1", "PEER_360", "COMPLETED", "peer-a", "2026-01-01T00:00:00Z"),
                        reviewItem("peer-2", "PEER_360", "DRAFT", "peer-b", "2026-01-01T00:00:00Z")
                )
        );

        ManagerGateStatus gate = reviewService.getManagerGateStatus("cycle-1", "alice");

        assertTrue(!gate.open());
        assertEquals(1, gate.peer360().completedCount());
        assertEquals(List.of("peer-b"), gate.peer360().incompleteAuthorUserIds());
        assertEquals("peer360_incomplete", gate.reasons().getFirst().type());
        assertEquals(1, gate.reasons().getFirst().missingCount());
    }

    @Test
    void gate_closed_combinesReasons_whenSelfDraftAndPeerDraft() {
        when(mockDynamoDb.query(any(QueryRequest.class))).thenReturn(
                query(reviewItem("self-1", "SELF", "DRAFT", "alice", "2026-01-01T00:00:00Z")),
                query(reviewItem("peer-1", "PEER_360", "NOT_STARTED", "peer-a", "2026-01-01T00:00:00Z"))
        );

        ManagerGateStatus gate = reviewService.getManagerGateStatus("cycle-1", "alice");

        assertTrue(!gate.open());
        assertEquals(2, gate.reasons().size());
        assertEquals("self_incomplete", gate.reasons().get(0).type());
        assertEquals("peer360_incomplete", gate.reasons().get(1).type());
    }

    @Test
    void gate_takesMostRecentSelf_whenDuplicates() {
        when(mockDynamoDb.query(any(QueryRequest.class))).thenReturn(
                query(
                        reviewItem("self-old", "SELF", "DRAFT", "alice", "2026-01-01T00:00:00Z"),
                        reviewItem("self-new", "SELF", "COMPLETED", "alice", "2026-01-02T00:00:00Z")
                ),
                query()
        );

        ManagerGateStatus gate = reviewService.getManagerGateStatus("cycle-1", "alice");

        assertTrue(gate.open());
        assertEquals("self-new", gate.selfReview().reviewId());
    }

    @Test
    void gate_publishedSelfCountsAsCompleted() {
        when(mockDynamoDb.query(any(QueryRequest.class))).thenReturn(
                query(reviewItem("self-1", "SELF", "PUBLISHED", "alice", "2026-01-01T00:00:00Z")),
                query()
        );

        ManagerGateStatus gate = reviewService.getManagerGateStatus("cycle-1", "alice");

        assertTrue(gate.open());
    }

    // ── Cycle-driven peer-360 requirement ─────────────────────────────────────

    @Test
    void gate_peer360NotRequired_whenCycleHasNoPeer360Milestone() {
        // Cycle exists but has no PEER_360 milestone (mockCycleService returns empty by default)
        when(mockDynamoDb.query(any(QueryRequest.class))).thenReturn(
                query(reviewItem("self-1", "SELF", "COMPLETED", "alice", "2026-01-01T00:00:00Z")),
                query()
        );

        ManagerGateStatus gate = reviewService.getManagerGateStatus("cycle-1", "alice");

        assertTrue(gate.open());
        assertEquals(false, gate.peer360().required());
        assertTrue(gate.reasons().isEmpty());
    }

    @Test
    void gate_closed_when_cycleRequiresPeer360_butNoAssignmentsExist() {
        // Cycle has a PEER_360 milestone — requires assignments
        when(mockCycleService.getReviewCycle("cycle-1"))
                .thenReturn(Optional.of(cycleWithPeer360Milestone()));
        when(mockDynamoDb.query(any(QueryRequest.class))).thenReturn(
                query(reviewItem("self-1", "SELF", "COMPLETED", "alice", "2026-01-01T00:00:00Z")),
                query() // empty PEER_360 set
        );

        ManagerGateStatus gate = reviewService.getManagerGateStatus("cycle-1", "alice");

        assertTrue(!gate.open());
        assertEquals(true, gate.peer360().required());
        assertEquals(0, gate.peer360().totalAssigned());
        assertEquals("peer360_unassigned", gate.reasons().getFirst().type());
    }

    @Test
    void gate_open_when_cycleRequiresPeer360_andAllAssignmentsCompleted() {
        when(mockCycleService.getReviewCycle("cycle-1"))
                .thenReturn(Optional.of(cycleWithPeer360Milestone()));
        when(mockDynamoDb.query(any(QueryRequest.class))).thenReturn(
                query(reviewItem("self-1", "SELF", "COMPLETED", "alice", "2026-01-01T00:00:00Z")),
                query(reviewItem("peer-1", "PEER_360", "COMPLETED", "peer-a", "2026-01-01T00:00:00Z"))
        );

        ManagerGateStatus gate = reviewService.getManagerGateStatus("cycle-1", "alice");

        assertTrue(gate.open());
        assertEquals(true, gate.peer360().required());
        assertEquals(1, gate.peer360().completedCount());
        assertTrue(gate.reasons().isEmpty());
    }

    @Test
    void gate_closed_when_cycleRequiresPeer360_andSomeAssignmentsIncomplete() {
        when(mockCycleService.getReviewCycle("cycle-1"))
                .thenReturn(Optional.of(cycleWithPeer360Milestone()));
        when(mockDynamoDb.query(any(QueryRequest.class))).thenReturn(
                query(reviewItem("self-1", "SELF", "COMPLETED", "alice", "2026-01-01T00:00:00Z")),
                query(
                        reviewItem("peer-1", "PEER_360", "COMPLETED", "peer-a", "2026-01-01T00:00:00Z"),
                        reviewItem("peer-2", "PEER_360", "DRAFT",     "peer-b", "2026-01-01T00:00:00Z")
                )
        );

        ManagerGateStatus gate = reviewService.getManagerGateStatus("cycle-1", "alice");

        assertTrue(!gate.open());
        assertEquals(true, gate.peer360().required());
        assertEquals(1, gate.peer360().completedCount());
        assertEquals("peer360_incomplete", gate.reasons().getFirst().type());
    }

    // ── MANAGER review dedup on create (one per cycle/subject) ─────────────────

    @Test
    void testCreate_DuplicateManagerReview_ThrowsConflict() {
        // A MANAGER review for (cycle-1, alice) already exists (e.g. authored by an admin
        // via "Start Review"). A reassigned/real manager re-creating it would duplicate.
        when(mockDynamoDb.query(any(QueryRequest.class))).thenReturn(
                query(reviewItem("mgr-existing", "MANAGER", "COMPLETED", "kevin-admin", NOW))
        );

        CreateReviewRequest req = new CreateReviewRequest();
        req.setCycleId("cycle-1");
        req.setTemplateId("tmpl-1");
        req.setSubjectUserId("alice");
        req.setAuthorUserId("ko-yun-manager");
        req.setReviewType(CreateReviewRequest.ReviewTypeEnum.MANAGER);

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> reviewService.create(req, "ko-yun-manager"));
        assertEquals(Response.Status.CONFLICT.getStatusCode(), ex.getResponse().getStatus());
        verify(mockDynamoDb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void testCreate_ManagerReview_NoExisting_Succeeds() {
        when(mockDynamoDb.query(any(QueryRequest.class))).thenReturn(query()); // none on file
        when(mockDynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(PutItemResponse.builder().build());

        CreateReviewRequest req = new CreateReviewRequest();
        req.setCycleId("cycle-1");
        req.setTemplateId("tmpl-1");
        req.setSubjectUserId("alice");
        req.setAuthorUserId("ko-yun-manager");
        req.setReviewType(CreateReviewRequest.ReviewTypeEnum.MANAGER);

        Review created = reviewService.create(req, "ko-yun-manager");

        assertEquals(Review.ReviewTypeEnum.MANAGER, created.getReviewType());
        verify(mockDynamoDb).putItem(any(PutItemRequest.class));
    }

    // ── publishBundle refuses an empty manager review ─────────────────────────

    @Test
    void testPublishBundle_EmptyManagerAnswers_Throws400() {
        // COMPLETED manager review but answers == [] (reviewItem default) — must not publish.
        when(mockDynamoDb.query(any(QueryRequest.class))).thenReturn(
                query(reviewItem("mgr-1", "MANAGER", "COMPLETED", "kevin-admin", NOW))
        );

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> reviewService.publishBundle("cycle-1", "alice", "kevin-admin"));
        assertTrue(ex.getMessage().toLowerCase().contains("answer"),
                "message should explain the empty-answers reason: " + ex.getMessage());
        verify(mockDynamoDb, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void testPublishBundle_ManagerWithAnswers_Publishes() {
        Map<String, AttributeValue> mgr = reviewItem("mgr-1", "MANAGER", "COMPLETED", "kevin-admin", NOW);
        mgr.put("answers", AttributeValue.builder()
                .s("[{\"questionId\":\"q1\",\"response\":\"Strong delivery this cycle.\"}]").build());
        when(mockDynamoDb.query(any(QueryRequest.class))).thenReturn(query(mgr));
        when(mockDynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(TransactWriteItemsResponse.builder().build());

        List<Review> result = reviewService.publishBundle("cycle-1", "alice", "kevin-admin");

        assertEquals(Review.StatusEnum.PUBLISHED, result.get(0).getStatus());
        verify(mockDynamoDb).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    /** Builds a minimal ReviewCycle with one milestone whose templateSnapshot.reviewType is PEER_360. */
    private ReviewCycle cycleWithPeer360Milestone() {
        TemplateSnapshot snap = new TemplateSnapshot();
        snap.setReviewType(TemplateSnapshot.ReviewTypeEnum.PEER_360);
        Milestone m = new Milestone();
        m.setMilestoneId("ms-1");
        m.setName("360 feedback");
        m.setTemplateSnapshot(snap);
        ReviewCycle c = new ReviewCycle();
        c.setCycleId("cycle-1");
        c.setMilestones(List.of(m));
        return c;
    }

    private Map<String, AttributeValue> buildItem(String reviewId) {
        return reviewItem(reviewId, "SELF", "NOT_STARTED", "alice", NOW);
    }

    @SafeVarargs
    private QueryResponse query(Map<String, AttributeValue>... items) {
        return QueryResponse.builder().items(items).build();
    }

    private Map<String, AttributeValue> reviewItem(
            String reviewId,
            String reviewType,
            String status,
            String authorUserId,
            String updatedAt) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("reviewId", AttributeValue.builder().s(reviewId).build());
        item.put("cycleId", AttributeValue.builder().s("cycle-1").build());
        item.put("templateId", AttributeValue.builder().s("tmpl-1").build());
        item.put("subjectUserId", AttributeValue.builder().s("alice").build());
        item.put("authorUserId", AttributeValue.builder().s(authorUserId).build());
        item.put("reviewType", AttributeValue.builder().s(reviewType).build());
        item.put("status", AttributeValue.builder().s(status).build());
        item.put("cycleTypeKey", AttributeValue.builder().s("cycle-1#" + reviewType).build());
        item.put("answers", AttributeValue.builder().s("[]").build());
        item.put("createdAt", AttributeValue.builder().s(NOW).build());
        item.put("updatedAt", AttributeValue.builder().s(updatedAt).build());
        item.put("version", AttributeValue.builder().n("1").build());
        return item;
    }
}

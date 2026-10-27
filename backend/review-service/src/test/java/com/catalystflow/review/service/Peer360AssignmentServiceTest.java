package com.catalystflow.review.service;

import com.catalystflow.review.model.CreatePeer360AssignmentsRequest;
import com.catalystflow.review.model.CreateReviewRequest;
import com.catalystflow.review.model.Peer360AssignmentResult;
import com.catalystflow.review.model.Review;
import com.catalystflow.review.model.ReviewCycle;
import com.catalystflow.review.model.ReviewTemplate;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class Peer360AssignmentServiceTest {

    @Inject
    Peer360AssignmentService service;

    @InjectMock
    ReviewService reviewService;

    @InjectMock
    ReviewCycleService cycleService;

    @InjectMock
    ReviewTemplateService templateService;

    @InjectMock
    UserLookup userLookup;

    private static final String CYCLE = "cycle-1";
    private static final String SUBJECT = "alice";
    private static final String MANAGER = "manager-id";
    private static final String TEMPLATE = "tpl-peer360";

    @BeforeEach
    void setupHappyPath() {
        // Cycle is ACTIVE by default
        ReviewCycle cycle = new ReviewCycle();
        cycle.setCycleId(CYCLE);
        cycle.setStatus(ReviewCycle.StatusEnum.ACTIVE);
        when(cycleService.getReviewCycle(CYCLE)).thenReturn(Optional.of(cycle));

        // Template is PEER_360
        ReviewTemplate template = new ReviewTemplate();
        template.setTemplateId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        template.setReviewType(ReviewTemplate.ReviewTypeEnum.PEER_360);
        when(templateService.getTemplate(TEMPLATE)).thenReturn(Optional.of(template));

        // All users active by default
        when(userLookup.existsActive(anyString())).thenReturn(true);

        // No existing assignments by default
        when(reviewService.getPeer360Assignments(CYCLE, SUBJECT)).thenReturn(List.of());

        // create() returns a stub Review echoing the input
        when(reviewService.create(any(CreateReviewRequest.class), anyString())).thenAnswer(inv -> {
            CreateReviewRequest req = inv.getArgument(0);
            Review row = new Review();
            row.setReviewId("review-" + req.getAuthorUserId());
            row.setCycleId(req.getCycleId());
            row.setSubjectUserId(req.getSubjectUserId());
            row.setAuthorUserId(req.getAuthorUserId());
            row.setReviewType(Review.ReviewTypeEnum.PEER_360);
            row.setStatus(Review.StatusEnum.NOT_STARTED);
            return row;
        });
    }

    private static CreatePeer360AssignmentsRequest req(String... authors) {
        CreatePeer360AssignmentsRequest r = new CreatePeer360AssignmentsRequest();
        r.setSubjectUserId(SUBJECT);
        r.setAuthorUserIds(List.of(authors));
        r.setTemplateId(TEMPLATE);
        return r;
    }

    private static Review existing(String reviewId, String authorUserId, Review.StatusEnum status) {
        Review r = new Review();
        r.setReviewId(reviewId);
        r.setCycleId(CYCLE);
        // Default existing rows use the same TEMPLATE the request passes — required by the
        // template-match check in createAssignments() when the (subject, author) pair already
        // has a row.
        r.setTemplateId(TEMPLATE);
        r.setSubjectUserId(SUBJECT);
        r.setAuthorUserId(authorUserId);
        r.setReviewType(Review.ReviewTypeEnum.PEER_360);
        r.setStatus(status);
        return r;
    }

    // ── 1 ────────────────────────────────────────────────────────────────────
    @Test
    void create_assignsAllNew() {
        Peer360AssignmentResult result = service.createAssignments(
                CYCLE, req("bob", "charlie", "dave"), MANAGER);

        assertEquals(3, result.getCreated().size());
        assertEquals(0, result.getSkipped().size());
        assertEquals(3, result.getAll().size());
        verify(reviewService, times(3)).create(any(CreateReviewRequest.class), eqUser(MANAGER));
    }

    // ── 2 ────────────────────────────────────────────────────────────────────
    @Test
    void create_isIdempotent_onRepeatedAuthor() {
        when(reviewService.getPeer360Assignments(CYCLE, SUBJECT))
                .thenReturn(List.of(existing("rev-bob", "bob", Review.StatusEnum.NOT_STARTED)));

        Peer360AssignmentResult result = service.createAssignments(
                CYCLE, req("bob", "charlie"), MANAGER);

        assertEquals(1, result.getCreated().size());
        assertEquals("charlie", result.getCreated().get(0).getAuthorUserId());
        assertEquals(1, result.getSkipped().size());
        assertEquals("bob", result.getSkipped().get(0).getAuthorUserId());
        verify(reviewService, times(1)).create(any(CreateReviewRequest.class), anyString());
    }

    // ── 3 ────────────────────────────────────────────────────────────────────
    @Test
    void create_rejects_selfAsPeer() {
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.createAssignments(CYCLE, req(SUBJECT, "bob"), MANAGER));
        assertTrue(ex.getMessage().toLowerCase().contains("subject"));
        verify(reviewService, never()).create(any(), any());
    }

    // ── 4 ────────────────────────────────────────────────────────────────────
    @Test
    void create_rejects_managerAsPeer() {
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.createAssignments(CYCLE, req(MANAGER, "bob"), MANAGER));
        assertTrue(ex.getMessage().toLowerCase().contains("manager"));
        verify(reviewService, never()).create(any(), any());
    }

    // ── 5 ────────────────────────────────────────────────────────────────────
    @Test
    void create_rejects_archivedAuthor() {
        when(userLookup.existsActive("bob")).thenReturn(false);
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.createAssignments(CYCLE, req("bob"), MANAGER));
        assertTrue(ex.getMessage().toLowerCase().contains("archived")
                || ex.getMessage().toLowerCase().contains("not found"));
        verify(reviewService, never()).create(any(), any());
    }

    // ── 6 ────────────────────────────────────────────────────────────────────
    @Test
    void create_rejects_inactiveCycle() {
        ReviewCycle cycle = new ReviewCycle();
        cycle.setCycleId(CYCLE);
        cycle.setStatus(ReviewCycle.StatusEnum.COMPLETED);
        when(cycleService.getReviewCycle(CYCLE)).thenReturn(Optional.of(cycle));

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.createAssignments(CYCLE, req("bob"), MANAGER));
        assertTrue(ex.getMessage().toLowerCase().contains("scheduled")
                || ex.getMessage().toLowerCase().contains("active"));
    }

    // ── 7 ────────────────────────────────────────────────────────────────────
    @Test
    void create_rejects_nonPeer360Template() {
        ReviewTemplate template = new ReviewTemplate();
        template.setTemplateId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        template.setReviewType(ReviewTemplate.ReviewTypeEnum.SELF);
        when(templateService.getTemplate(TEMPLATE)).thenReturn(Optional.of(template));

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.createAssignments(CYCLE, req("bob"), MANAGER));
        assertTrue(ex.getMessage().toLowerCase().contains("peer_360"));
    }

    // ── 8 ────────────────────────────────────────────────────────────────────
    @Test
    void list_returnsOnlyPeer360_forSubjectAndCycle() {
        when(reviewService.getPeer360Assignments(CYCLE, SUBJECT)).thenReturn(List.of(
                existing("rev-bob", "bob", Review.StatusEnum.NOT_STARTED),
                existing("rev-charlie", "charlie", Review.StatusEnum.COMPLETED)
        ));

        List<Review> rows = service.listAssignments(CYCLE, SUBJECT);

        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(r -> r.getReviewType() == Review.ReviewTypeEnum.PEER_360));
    }

    // ── 9 ────────────────────────────────────────────────────────────────────
    @Test
    void delete_succeeds_onNotStarted() {
        when(reviewService.getReviewById("rev-bob"))
                .thenReturn(Optional.of(existing("rev-bob", "bob", Review.StatusEnum.NOT_STARTED)));

        Peer360AssignmentService.DeleteOutcome outcome = service.deleteAssignment("rev-bob");

        assertEquals(Peer360AssignmentService.DeleteOutcome.DELETED, outcome);
        verify(reviewService).delete("rev-bob");
    }

    // ── 10 ───────────────────────────────────────────────────────────────────
    @Test
    void delete_returns_inProgress_onNonNotStarted() {
        when(reviewService.getReviewById("rev-bob"))
                .thenReturn(Optional.of(existing("rev-bob", "bob", Review.StatusEnum.IN_PROGRESS)));

        Peer360AssignmentService.DeleteOutcome outcome = service.deleteAssignment("rev-bob");

        assertEquals(Peer360AssignmentService.DeleteOutcome.IN_PROGRESS, outcome);
        verify(reviewService, never()).delete(anyString());
    }

    // ── 11 ───────────────────────────────────────────────────────────────────
    @Test
    void delete_returns_notFound_onMissing() {
        when(reviewService.getReviewById("ghost")).thenReturn(Optional.empty());
        Peer360AssignmentService.DeleteOutcome outcome = service.deleteAssignment("ghost");
        assertEquals(Peer360AssignmentService.DeleteOutcome.NOT_FOUND, outcome);
    }

    // ── 12 ───────────────────────────────────────────────────────────────────
    @Test
    void delete_returns_notFound_whenIdBelongsToNonPeer360Review() {
        Review selfRow = existing("rev-self", "alice", Review.StatusEnum.NOT_STARTED);
        selfRow.setReviewType(Review.ReviewTypeEnum.SELF);
        when(reviewService.getReviewById("rev-self")).thenReturn(Optional.of(selfRow));

        Peer360AssignmentService.DeleteOutcome outcome = service.deleteAssignment("rev-self");
        assertEquals(Peer360AssignmentService.DeleteOutcome.NOT_FOUND, outcome);
        verify(reviewService, never()).delete(anyString());
    }

    // ── 13 ───────────────────────────────────────────────────────────────────
    @Test
    void create_rejects_whenExistingRowUsesDifferentTemplate() {
        // Existing row for bob uses an older template — request targets a new one.
        Review staleRow = existing("rev-bob", "bob", Review.StatusEnum.NOT_STARTED);
        staleRow.setTemplateId("tpl-old");
        when(reviewService.getPeer360Assignments(CYCLE, SUBJECT)).thenReturn(List.of(staleRow));

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.createAssignments(CYCLE, req("bob"), MANAGER));

        assertTrue(ex.getMessage().toLowerCase().contains("different template"),
                "Expected mismatch error, got: " + ex.getMessage());
        verify(reviewService, never()).create(any(), any());
    }

    // ── 14 ───────────────────────────────────────────────────────────────────
    @Test
    void create_idempotent_whenExistingRowUsesSameTemplate() {
        // Same author re-assigned with same templateId → silent skip (existing behaviour preserved).
        Review match = existing("rev-bob", "bob", Review.StatusEnum.NOT_STARTED);
        when(reviewService.getPeer360Assignments(CYCLE, SUBJECT)).thenReturn(List.of(match));

        Peer360AssignmentResult result = service.createAssignments(CYCLE, req("bob"), MANAGER);

        assertEquals(0, result.getCreated().size());
        assertEquals(1, result.getSkipped().size());
        assertEquals("bob", result.getSkipped().get(0).getAuthorUserId());
    }

    // ── helper to make Mockito match the userId argument cleanly ─────────────
    private static String eqUser(String s) {
        return org.mockito.ArgumentMatchers.eq(s);
    }
}

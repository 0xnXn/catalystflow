package com.catalystflow.review.service;

import com.catalystflow.review.model.CreatePeer360AssignmentsRequest;
import com.catalystflow.review.model.CreateReviewRequest;
import com.catalystflow.review.model.Peer360AssignmentResult;
import com.catalystflow.review.model.Review;
import com.catalystflow.review.model.ReviewCycle;
import com.catalystflow.review.model.ReviewTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates manager-driven 360 reviewer assignments.
 *
 * <p>Each assignment becomes a Review row with reviewType=PEER_360. Idempotent on the
 * (subjectUserId, authorUserId) pair — re-POSTing the same pair returns the existing
 * row in {@code skipped} rather than creating a duplicate.
 *
 * <p>The manager-review gate ({@link ReviewService#getManagerGateStatus}) reads the
 * rows this service creates — once at least one PEER_360 row exists, the gate starts
 * gating manager-review submission on its completion.
 */
@ApplicationScoped
public class Peer360AssignmentService {

    private static final Logger LOG = Logger.getLogger(Peer360AssignmentService.class);
    public static final int MAX_PEERS_PER_BATCH = 10;

    @Inject
    ReviewService reviewService;

    @Inject
    ReviewCycleService cycleService;

    @Inject
    ReviewTemplateService templateService;

    @Inject
    UserLookup users;

    /**
     * Create (or skip) PEER_360 assignment rows.
     *
     * <p>Validation rules (all return 400 / {@link BadRequestException}):
     * <ul>
     *   <li>Cycle exists and {@code status} is SCHEDULED or ACTIVE.</li>
     *   <li>Template exists and its {@code reviewType} is PEER_360.</li>
     *   <li>Subject exists and is not Archived.</li>
     *   <li>Each author exists and is not Archived.</li>
     *   <li>Each author is not the subject (no self-as-peer).</li>
     *   <li>Each author is not the manager creating it (manager and 360 reviewer are distinct roles).</li>
     *   <li>{@code authorUserIds.size()} ≤ {@link #MAX_PEERS_PER_BATCH}.</li>
     * </ul>
     */
    public Peer360AssignmentResult createAssignments(String cycleId,
                                                     CreatePeer360AssignmentsRequest request,
                                                     String createdBy) {
        if (request == null) throw new BadRequestException("Request body is required");
        String subjectUserId = request.getSubjectUserId();
        List<String> authorUserIds = request.getAuthorUserIds();
        String templateId = request.getTemplateId();

        if (subjectUserId == null || subjectUserId.isBlank()) {
            throw new BadRequestException("'subjectUserId' is required");
        }
        if (authorUserIds == null || authorUserIds.isEmpty()) {
            throw new BadRequestException("'authorUserIds' must be non-empty");
        }
        if (authorUserIds.size() > MAX_PEERS_PER_BATCH) {
            throw new BadRequestException("Max " + MAX_PEERS_PER_BATCH + " peers per batch");
        }
        if (templateId == null || templateId.isBlank()) {
            throw new BadRequestException("'templateId' is required");
        }

        // Cycle
        ReviewCycle cycle = cycleService.getReviewCycle(cycleId)
                .orElseThrow(() -> new BadRequestException("Cycle not found: " + cycleId));
        ReviewCycle.StatusEnum cycleStatus = cycle.getStatus();
        if (cycleStatus != ReviewCycle.StatusEnum.SCHEDULED && cycleStatus != ReviewCycle.StatusEnum.ACTIVE) {
            throw new BadRequestException("Cycle must be SCHEDULED or ACTIVE; was " + cycleStatus);
        }

        // Template — must exist and be a PEER_360 template
        ReviewTemplate template = templateService.getTemplate(templateId)
                .orElseThrow(() -> new BadRequestException("Template not found: " + templateId));
        if (template.getReviewType() != ReviewTemplate.ReviewTypeEnum.PEER_360) {
            throw new BadRequestException(
                    "Template reviewType must be PEER_360; was " + template.getReviewType());
        }

        // Subject must be a real, active user
        if (!users.existsActive(subjectUserId)) {
            throw new BadRequestException("Subject not found or archived: " + subjectUserId);
        }

        // Author validation — dedupe and check each
        Set<String> uniqueAuthors = new HashSet<>(authorUserIds);
        for (String authorUserId : uniqueAuthors) {
            if (authorUserId.equals(subjectUserId)) {
                throw new BadRequestException(
                        "Cannot assign subject as their own peer reviewer: " + authorUserId);
            }
            if (authorUserId.equals(createdBy)) {
                throw new BadRequestException(
                        "Manager cannot assign self as peer: " + authorUserId);
            }
            if (!users.existsActive(authorUserId)) {
                throw new BadRequestException(
                        "Author not found or archived: " + authorUserId);
            }
        }

        // Existing PEER_360 rows for this (cycle, subject)
        List<Review> existing = reviewService.getPeer360Assignments(cycleId, subjectUserId);

        // Create new rows or report as skipped
        List<Review> created = new ArrayList<>();
        List<Review> skipped = new ArrayList<>();
        for (String authorUserId : uniqueAuthors) {
            Review existingRow = existing.stream()
                    .filter(r -> authorUserId.equals(r.getAuthorUserId()))
                    .findFirst()
                    .orElse(null);
            if (existingRow != null) {
                // Idempotency must include templateId — silently re-using a row keyed only
                // on (subject, author) hides admin template swaps and lets peers fill the
                // wrong questionnaire. Reject so the caller knows their request was a no-op
                // due to a template mismatch.
                if (!templateId.equals(existingRow.getTemplateId())) {
                    throw new BadRequestException(
                            "Existing PEER_360 assignment for author " + authorUserId
                                    + " uses a different template (" + existingRow.getTemplateId()
                                    + "). Delete it before assigning a new template.");
                }
                skipped.add(existingRow);
                continue;
            }
            CreateReviewRequest req = new CreateReviewRequest();
            req.setCycleId(cycleId);
            req.setTemplateId(templateId);
            req.setSubjectUserId(subjectUserId);
            req.setAuthorUserId(authorUserId);
            req.setReviewType(CreateReviewRequest.ReviewTypeEnum.PEER_360);
            Review row = reviewService.create(req, createdBy);
            created.add(row);
        }

        Peer360AssignmentResult result = new Peer360AssignmentResult();
        result.setCreated(created);
        result.setSkipped(skipped);
        List<Review> all = new ArrayList<>(skipped);
        all.addAll(created);
        result.setAll(all);

        LOG.infof("Peer360 batch: cycle=%s subject=%s created=%d skipped=%d",
                cycleId, subjectUserId, created.size(), skipped.size());
        return result;
    }

    public List<Review> listAssignments(String cycleId, String subjectUserId) {
        return reviewService.getPeer360Assignments(cycleId, subjectUserId);
    }

    /** Fetch a PEER_360 assignment row by id. Returns empty if not found or not PEER_360. */
    public Optional<Review> getAssignment(String reviewId) {
        return reviewService.getReviewById(reviewId)
                .filter(r -> r.getReviewType() == Review.ReviewTypeEnum.PEER_360);
    }

    /**
     * Delete an assignment if the peer hasn't started.
     * <ul>
     *   <li>{@link DeleteOutcome#DELETED} — row existed (PEER_360, NOT_STARTED) and was deleted.</li>
     *   <li>{@link DeleteOutcome#NOT_FOUND} — no PEER_360 row with that id.</li>
     *   <li>{@link DeleteOutcome#IN_PROGRESS} — row exists but status != NOT_STARTED.</li>
     * </ul>
     */
    public DeleteOutcome deleteAssignment(String reviewId) {
        Optional<Review> opt = getAssignment(reviewId);
        if (opt.isEmpty()) return DeleteOutcome.NOT_FOUND;

        Review r = opt.get();
        if (r.getStatus() != Review.StatusEnum.NOT_STARTED) {
            return DeleteOutcome.IN_PROGRESS;
        }

        reviewService.delete(reviewId);
        LOG.infof("Peer360 deleted assignment %s (cycle=%s subject=%s author=%s)",
                reviewId, r.getCycleId(), r.getSubjectUserId(), r.getAuthorUserId());
        return DeleteOutcome.DELETED;
    }

    public enum DeleteOutcome { DELETED, NOT_FOUND, IN_PROGRESS }
}

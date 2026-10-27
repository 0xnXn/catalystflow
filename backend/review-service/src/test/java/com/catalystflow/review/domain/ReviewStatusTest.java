package com.catalystflow.review.domain;

import com.catalystflow.review.model.Review;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link ReviewStatus#canTransition} — no Quarkus bootstrap,
 * no DI. Exercises every branch of the review-state-machine switch.
 */
class ReviewStatusTest {

    // ── self-transitions always allowed ─────────────────────────────────────

    @Test
    void selfTransition_alwaysAllowed() {
        for (Review.StatusEnum s : Review.StatusEnum.values()) {
            assertTrue(ReviewStatus.canTransition(Review.ReviewTypeEnum.SELF, s, s),
                    "Self-transition should be allowed for " + s);
        }
    }

    // ── NOT_STARTED → ──────────────────────────────────────────────────────

    @Test
    void notStarted_toDraft_allowed() {
        assertTrue(ReviewStatus.canTransition(Review.ReviewTypeEnum.SELF,
                Review.StatusEnum.NOT_STARTED, Review.StatusEnum.DRAFT));
    }

    @Test
    void notStarted_toInProgress_allowed() {
        assertTrue(ReviewStatus.canTransition(Review.ReviewTypeEnum.SELF,
                Review.StatusEnum.NOT_STARTED, Review.StatusEnum.IN_PROGRESS));
    }

    @Test
    void notStarted_toCompleted_allowed() {
        assertTrue(ReviewStatus.canTransition(Review.ReviewTypeEnum.SELF,
                Review.StatusEnum.NOT_STARTED, Review.StatusEnum.COMPLETED));
    }

    @Test
    void notStarted_toPublished_rejected() {
        assertFalse(ReviewStatus.canTransition(Review.ReviewTypeEnum.MANAGER,
                Review.StatusEnum.NOT_STARTED, Review.StatusEnum.PUBLISHED));
    }

    // ── DRAFT → ────────────────────────────────────────────────────────────

    @Test
    void draft_toInProgress_allowed() {
        assertTrue(ReviewStatus.canTransition(Review.ReviewTypeEnum.SELF,
                Review.StatusEnum.DRAFT, Review.StatusEnum.IN_PROGRESS));
    }

    @Test
    void draft_toCompleted_allowed() {
        assertTrue(ReviewStatus.canTransition(Review.ReviewTypeEnum.SELF,
                Review.StatusEnum.DRAFT, Review.StatusEnum.COMPLETED));
    }

    @Test
    void draft_toPublished_rejected() {
        assertFalse(ReviewStatus.canTransition(Review.ReviewTypeEnum.MANAGER,
                Review.StatusEnum.DRAFT, Review.StatusEnum.PUBLISHED));
    }

    @Test
    void draft_toNotStarted_rejected() {
        assertFalse(ReviewStatus.canTransition(Review.ReviewTypeEnum.SELF,
                Review.StatusEnum.DRAFT, Review.StatusEnum.NOT_STARTED));
    }

    // ── IN_PROGRESS → ──────────────────────────────────────────────────────

    @Test
    void inProgress_toCompleted_allowed() {
        assertTrue(ReviewStatus.canTransition(Review.ReviewTypeEnum.SELF,
                Review.StatusEnum.IN_PROGRESS, Review.StatusEnum.COMPLETED));
    }

    @Test
    void inProgress_toDraft_rejected() {
        // Forward-only state machine
        assertFalse(ReviewStatus.canTransition(Review.ReviewTypeEnum.SELF,
                Review.StatusEnum.IN_PROGRESS, Review.StatusEnum.DRAFT));
    }

    @Test
    void inProgress_toPublished_rejected() {
        assertFalse(ReviewStatus.canTransition(Review.ReviewTypeEnum.MANAGER,
                Review.StatusEnum.IN_PROGRESS, Review.StatusEnum.PUBLISHED));
    }

    // ── COMPLETED → PUBLISHED only for MANAGER and PEER_360 ────────────────

    @Test
    void completed_toPublished_managerAllowed() {
        assertTrue(ReviewStatus.canTransition(Review.ReviewTypeEnum.MANAGER,
                Review.StatusEnum.COMPLETED, Review.StatusEnum.PUBLISHED));
    }

    @Test
    void completed_toPublished_peer360Allowed() {
        assertTrue(ReviewStatus.canTransition(Review.ReviewTypeEnum.PEER_360,
                Review.StatusEnum.COMPLETED, Review.StatusEnum.PUBLISHED));
    }

    @Test
    void completed_toPublished_selfRejected() {
        // SELF reviews never get PUBLISHED — only MANAGER/PEER_360 are visible to the subject
        assertFalse(ReviewStatus.canTransition(Review.ReviewTypeEnum.SELF,
                Review.StatusEnum.COMPLETED, Review.StatusEnum.PUBLISHED));
    }

    @Test
    void completed_toDraft_rejected() {
        assertFalse(ReviewStatus.canTransition(Review.ReviewTypeEnum.MANAGER,
                Review.StatusEnum.COMPLETED, Review.StatusEnum.DRAFT));
    }

    @Test
    void completed_toInProgress_rejected() {
        assertFalse(ReviewStatus.canTransition(Review.ReviewTypeEnum.MANAGER,
                Review.StatusEnum.COMPLETED, Review.StatusEnum.IN_PROGRESS));
    }

    // ── PUBLISHED is terminal ──────────────────────────────────────────────

    @Test
    void published_anyTransition_rejected() {
        for (Review.StatusEnum target : Review.StatusEnum.values()) {
            if (target == Review.StatusEnum.PUBLISHED) continue; // self-transition allowed
            assertFalse(ReviewStatus.canTransition(Review.ReviewTypeEnum.MANAGER,
                            Review.StatusEnum.PUBLISHED, target),
                    "Published is terminal — transition to " + target + " must be rejected");
        }
    }
}

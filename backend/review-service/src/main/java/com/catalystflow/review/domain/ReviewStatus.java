package com.catalystflow.review.domain;

import com.catalystflow.review.model.Review;

/**
 * Domain-only review status transition rules.
 */
public final class ReviewStatus {

    private ReviewStatus() {
    }

    public static boolean canTransition(
            Review.ReviewTypeEnum type,
            Review.StatusEnum current,
            Review.StatusEnum target
    ) {
        if (current == target) return true;
        return switch (current) {
            case NOT_STARTED -> target == Review.StatusEnum.DRAFT
                    || target == Review.StatusEnum.IN_PROGRESS
                    || target == Review.StatusEnum.COMPLETED;
            case DRAFT -> target == Review.StatusEnum.IN_PROGRESS
                    || target == Review.StatusEnum.DRAFT
                    || target == Review.StatusEnum.COMPLETED;
            case IN_PROGRESS -> target == Review.StatusEnum.COMPLETED;
            case COMPLETED -> target == Review.StatusEnum.PUBLISHED
                    && (type == Review.ReviewTypeEnum.MANAGER || type == Review.ReviewTypeEnum.PEER_360);
            case PUBLISHED -> false;
        };
    }
}

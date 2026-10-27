package com.catalystflow.review.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReviewTypeTest {

    @Test
    void values_areExactlySelfPeer360Manager_inOrder() {
        Review.ReviewTypeEnum[] expected = {
            Review.ReviewTypeEnum.SELF,
            Review.ReviewTypeEnum.PEER_360,
            Review.ReviewTypeEnum.MANAGER
        };
        assertArrayEquals(expected, Review.ReviewTypeEnum.values());
        assertEquals(3, Review.ReviewTypeEnum.values().length);
    }

    @Test
    void fromValue_doesNotKnowHrOrAdmin() {
        assertThrows(IllegalArgumentException.class, () -> Review.ReviewTypeEnum.fromValue("HR"));
        assertThrows(IllegalArgumentException.class, () -> Review.ReviewTypeEnum.fromValue("ADMIN"));
    }

    @Test
    void fromValue_acceptsPeer360() {
        assertEquals(Review.ReviewTypeEnum.PEER_360, Review.ReviewTypeEnum.fromValue("PEER_360"));
    }
}

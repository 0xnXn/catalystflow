package com.catalystflow.ai.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pure unit test for {@link AiReviewService#scrubDanglingCitations} — the
 * 2026-05-25 hotfix follow-up that strips {@code [cN]} tags whose key isn't
 * present in the citations map. Defends against Gemini regressing on the
 * schema-required citations contract (emitting empty {@code {}} while still
 * embedding {@code [cN]} markers inline in the prose).
 *
 * <p>No {@code @QuarkusTest} needed — the helper is package-private static.
 */
class AiReviewServiceCitationScrubTest {

    @Test
    void noMarkersAndNullCitations_returnsTextUnchanged() {
        String text = "Plain prose with no citation markers at all.";
        String result = AiReviewService.scrubDanglingCitations(text, null);
        assertSame(text, result, "no-op fast path: same reference back");
    }

    @Test
    void someMarkersPresent_stripsOnlyTheUnmatchedOnes() {
        // c1 is in citations, c2 is dangling — only c2 should be stripped.
        String text = "Anna shipped the spike [c1] and led the demo [c2] in week 3.";
        Map<String, String> citations = Map.of("c1", "evidence-1");
        String result = AiReviewService.scrubDanglingCitations(text, citations);
        assertEquals("Anna shipped the spike [c1] and led the demo in week 3.", result,
                "c1 kept, c2 stripped, trailing-space cleanup applied (no double-space)");
    }

    @Test
    void nullCitationsWithMarkers_stripsAll() {
        String text = "Owned delivery [c1] and pairing [c2] and design reviews [c3] this quarter.";
        String result = AiReviewService.scrubDanglingCitations(text, null);
        assertEquals("Owned delivery and pairing and design reviews this quarter.", result,
                "every [cN] stripped when no citations map exists; prose remains readable");
    }

    @Test
    void allMarkersMatched_returnsTextUnchanged() {
        String text = "Took ownership [c1] and unblocked the team [c5] repeatedly.";
        Map<String, String> citations = Map.of("c1", "x", "c5", "y");
        String result = AiReviewService.scrubDanglingCitations(text, citations);
        assertSame(text, result, "no scrub needed → same reference back (avoids needless alloc)");
    }

    @Test
    void trailingMarkerBeforePeriod_preservesPunctuation() {
        // Regression case from the original ticket: "Did the thing [c1]." → "Did the thing."
        // Period must survive and no orphan space must remain before it.
        String text = "Did the thing [c1].";
        String result = AiReviewService.scrubDanglingCitations(text, Map.of());
        assertEquals("Did the thing.", result,
                "trailing [c1] stripped; period preserved; no orphan space before period");
    }
}

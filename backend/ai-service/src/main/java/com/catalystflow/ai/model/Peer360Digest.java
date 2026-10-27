package com.catalystflow.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * AI-generated digest of all completed PEER_360 reviews for a single
 * (cycleId, subjectUserId) pair. Supplies the manager with themes,
 * consensus, and dissent across peer voices — decision support that
 * sits alongside the raw 360 reviews in the manager view (ADR-2).
 *
 * Citation tokens (e.g. {@code [c1]}) inside narrative strings reference
 * entries Gemini may emit in {@code citations}; the schema does not
 * require citations yet — that's deferred to the citations slice.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Peer360Digest {

    public List<Theme> themes;
    public List<Point> consensus;
    public List<Point> dissent;
    public String overallSentiment;

    /** Allowed values for {@link #overallSentiment}. */
    public static final List<String> ALLOWED_SENTIMENTS =
            List.of("positive", "mixed", "constructive", "concerning");

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Theme {
        public String theme;
        public String summary;
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Point {
        public String point;
        /** Optional list of {reviewId} the point draws from. */
        public List<String> reviewIds;
    }

    /**
     * Validate this instance against the ADR-2 schema. Returns a list of
     * human-readable error strings — empty list means valid.
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (themes == null) errors.add("themes is required (use [] when none)");
        else for (int i = 0; i < themes.size(); i++) {
            Theme t = themes.get(i);
            if (t == null || isBlank(t.theme)) errors.add("themes[" + i + "].theme is required");
        }
        if (consensus == null) errors.add("consensus is required (use [] when none)");
        else for (int i = 0; i < consensus.size(); i++) {
            Point p = consensus.get(i);
            if (p == null || isBlank(p.point)) errors.add("consensus[" + i + "].point is required");
        }
        if (dissent == null) errors.add("dissent is required (use [] when none)");
        else for (int i = 0; i < dissent.size(); i++) {
            Point p = dissent.get(i);
            if (p == null || isBlank(p.point)) errors.add("dissent[" + i + "].point is required");
        }
        if (isBlank(overallSentiment) || !ALLOWED_SENTIMENTS.contains(overallSentiment)) {
            errors.add("overallSentiment must be one of " + ALLOWED_SENTIMENTS);
        }
        return errors;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

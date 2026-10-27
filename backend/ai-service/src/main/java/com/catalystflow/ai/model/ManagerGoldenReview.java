package com.catalystflow.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Composite "golden" performance review per Anna's prescribed structure.
 * Fixed schema — identical for every employee — so admins/HR can read across
 * subjects without per-cycle rendering surprises.
 *
 * Ratings are integers 1..5 (1 = Below Expectation, 5 = Exceeds Expectation).
 * Comment fields are narrative prose; citation tokens like "[c1]" inline with
 * the matching {@code citations} entry will be added in a follow-up slice.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ManagerGoldenReview {
    public Integer technologyRating;
    public String technologyComments;
    public Integer consultingRating;
    public String consultingComments;
    public String companyValuesComments;
    public String developmentOpportunities;
    public String otherComments;
    public Integer overallRating;

    /**
     * Optional map of citation key (e.g. "c1") → source metadata. Narrative
     * fields may embed tokens like {@code [c1]} which the frontend renders as
     * clickable chips. Empty/null is valid — Gemini may produce a citation-free
     * narrative for short inputs. ADR-3.
     */
    public Map<String, Citation> citations;

    /** Source pointer for a single citation token. */
    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Citation {
        /** {@code "self"} or {@code "peer_360"}. */
        public String source;
        public String reviewId;
        public String questionId;
        /** Verbatim quoted substring from the cited answer. Frontend uses this
         *  to locate + highlight the span (more robust than character offsets). */
        public String quote;
        /** Only present when source is {@code peer_360}. Manager-only. */
        public String authorUserId;
    }

    /**
     * Validate this instance against the ADR-1 schema. Returns a list of
     * human-readable error strings — empty list means the instance is valid.
     * Worker code uses this to drive the validate → repair → validate → fail
     * loop in {@link com.catalystflow.ai.service.ManagerGuidanceService}.
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (!isRating(technologyRating)) errors.add("technologyRating must be 1..5 integer");
        if (isBlank(technologyComments)) errors.add("technologyComments is required");
        if (!isRating(consultingRating)) errors.add("consultingRating must be 1..5 integer");
        if (isBlank(consultingComments)) errors.add("consultingComments is required");
        if (isBlank(companyValuesComments)) errors.add("companyValuesComments is required");
        if (isBlank(developmentOpportunities)) errors.add("developmentOpportunities is required");
        if (isBlank(otherComments)) errors.add("otherComments is required");
        if (!isRating(overallRating)) errors.add("overallRating must be 1..5 integer");

        // Step 1 — shape-validate EVERY citation entry, referenced or not.
        // Pre-RS-2-follow-up this loop only ran on entries with a [cN] token
        // in the prose, so Gemini could emit garbage unreferenced entries
        // (e.g. citations = {"c99": {"source":"peer_360"}}) that landed in
        // resultJson unchallenged. Frontend never expected unreferenced
        // entries but had no defence; persisted data was wrong.
        //
        // Also enforced by ResponseSchemas at the Gemini layer, but kept here
        // as a defensive last line because (a) Gemini's required-list honour
        // on additionalProperties value schemas is best-effort, and (b) this
        // code runs against historical rows persisted before the schema
        // tightening landed.
        if (citations != null) {
            for (java.util.Map.Entry<String, Citation> entry : citations.entrySet()) {
                String key = entry.getKey();
                Citation c = entry.getValue();
                if (c == null) {
                    errors.add("citation [" + key + "] entry is null");
                    continue;
                }
                if (isBlank(c.source))     errors.add("citation [" + key + "] source is required");
                if (isBlank(c.reviewId))   errors.add("citation [" + key + "] reviewId is required");
                if (isBlank(c.questionId)) errors.add("citation [" + key + "] questionId is required");
                if (isBlank(c.quote))      errors.add("citation [" + key + "] quote is required");
                // Conditional rule that Gemini's OpenAPI subset cannot express
                // (no if/then/else): peer_360 citations must identify the author
                // so the manager can trace the feedback back to a specific peer.
                if ("peer_360".equals(c.source) && isBlank(c.authorUserId)) {
                    errors.add("citation [" + key + "] peer_360 source requires authorUserId");
                }
            }
        }

        // Step 2 — referential integrity: every [cN] token in any narrative
        // must have a matching entry in the citations map. Citations map may
        // be null/empty (Gemini sometimes produces citation-free narrative);
        // that's only an error if there are unresolved tokens in the prose.
        Pattern token = Pattern.compile("\\[(c\\d+)\\]");
        java.util.Set<String> referenced = new java.util.LinkedHashSet<>();
        for (String text : List.of(
                nullSafe(technologyComments),
                nullSafe(consultingComments),
                nullSafe(companyValuesComments),
                nullSafe(developmentOpportunities),
                nullSafe(otherComments))) {
            Matcher m = token.matcher(text);
            while (m.find()) referenced.add(m.group(1));
        }
        for (String key : referenced) {
            if (citations == null || !citations.containsKey(key) || citations.get(key) == null) {
                errors.add("citation [" + key + "] referenced in narrative but missing from citations map");
            }
            // Shape errors were already collected in Step 1 — no need to repeat.
        }
        return errors;
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    private static boolean isRating(Integer r) {
        return r != null && r >= 1 && r <= 5;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

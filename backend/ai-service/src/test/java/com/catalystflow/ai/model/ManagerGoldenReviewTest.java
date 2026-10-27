package com.catalystflow.ai.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schema validation tests for the ADR-1 golden review contract.
 * Pure unit — no Quarkus, no Gemini.
 */
class ManagerGoldenReviewTest {

    private static ManagerGoldenReview valid() {
        ManagerGoldenReview g = new ManagerGoldenReview();
        g.technologyRating = 4;
        g.technologyComments = "Strong delivery leadership.";
        g.consultingRating = 3;
        g.consultingComments = "Effective stakeholder management.";
        g.companyValuesComments = "Demonstrates pragmatic delivery.";
        g.developmentOpportunities = "Presentation confidence.";
        g.otherComments = "Solid overall performance.";
        g.overallRating = 4;
        return g;
    }

    @Test
    void validate_fullyPopulated_returnsNoErrors() {
        assertTrue(valid().validate().isEmpty());
    }

    @Test
    void validate_missingComments_flagsField() {
        ManagerGoldenReview g = valid();
        g.technologyComments = null;
        List<String> errors = g.validate();
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("technologyComments"));
    }

    @Test
    void validate_blankComments_flagsField() {
        ManagerGoldenReview g = valid();
        g.companyValuesComments = "   ";
        assertTrue(g.validate().stream().anyMatch(e -> e.contains("companyValuesComments")));
    }

    @Test
    void validate_ratingOutOfRange_flagsField() {
        ManagerGoldenReview g = valid();
        g.technologyRating = 6;
        assertTrue(g.validate().stream().anyMatch(e -> e.contains("technologyRating")));
        g.technologyRating = 0;
        assertTrue(g.validate().stream().anyMatch(e -> e.contains("technologyRating")));
    }

    @Test
    void validate_ratingNull_flagsField() {
        ManagerGoldenReview g = valid();
        g.overallRating = null;
        assertTrue(g.validate().stream().anyMatch(e -> e.contains("overallRating")));
    }

    @Test
    void validate_completelyEmpty_flagsAllRequiredFields() {
        ManagerGoldenReview g = new ManagerGoldenReview();
        List<String> errors = g.validate();
        assertEquals(8, errors.size(), "All eight fields are required");
    }

    @Test
    void validate_citationTokenWithoutEntry_flagsError() {
        ManagerGoldenReview g = valid();
        g.technologyComments = "Strong delivery [c1] and ownership.";
        // citations map missing → unresolved [c1]
        List<String> errors = g.validate();
        assertTrue(errors.stream().anyMatch(e -> e.contains("[c1]") && e.contains("missing")),
                "Expected error about [c1] referenced but missing, got: " + errors);
    }

    @Test
    void validate_citationEntryMissingAllFields_flagsEveryRequiredOne() {
        ManagerGoldenReview g = valid();
        g.technologyComments = "Strong delivery [c1].";
        ManagerGoldenReview.Citation c = new ManagerGoldenReview.Citation();
        // Every field blank — should flag source + reviewId + questionId + quote
        // (4 separate per-field errors per the RS-2 follow-up tightening).
        g.citations = java.util.Map.of("c1", c);
        List<String> errors = g.validate();
        assertTrue(errors.stream().anyMatch(e -> e.contains("[c1] source is required")),
                "missing source must be flagged: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("[c1] reviewId is required")),
                "missing reviewId must be flagged: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("[c1] questionId is required")),
                "missing questionId must be flagged (RS-2 tightening): " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("[c1] quote is required")),
                "missing quote must be flagged (RS-2 tightening): " + errors);
    }

    @Test
    void validate_citationMissingQuestionId_isRejected() {
        // Pre-RS-2 follow-up this was silently accepted — every citation now
        // needs questionId so the manager can trace which question the quote
        // came from.
        ManagerGoldenReview g = valid();
        g.technologyComments = "Strong delivery [c1].";
        ManagerGoldenReview.Citation c = new ManagerGoldenReview.Citation();
        c.source = "self";
        c.reviewId = "r-self-1";
        c.quote = "shipped on time";
        // questionId left null
        g.citations = java.util.Map.of("c1", c);
        assertTrue(g.validate().stream().anyMatch(e -> e.contains("[c1] questionId is required")),
                "questionId is mandatory for every citation");
    }

    @Test
    void validate_citationMissingQuote_isRejected() {
        // Pre-RS-2 follow-up a peer reference with no quote left the manager
        // unable to audit the claim — now an explicit validation error.
        ManagerGoldenReview g = valid();
        g.technologyComments = "Strong delivery [c1].";
        ManagerGoldenReview.Citation c = new ManagerGoldenReview.Citation();
        c.source = "peer_360";
        c.reviewId = "r-360-bob";
        c.questionId = "q1";
        c.authorUserId = "bob";
        // quote left null
        g.citations = java.util.Map.of("c1", c);
        assertTrue(g.validate().stream().anyMatch(e -> e.contains("[c1] quote is required")),
                "quote is mandatory for every citation");
    }

    @Test
    void validate_peer360WithoutAuthorUserId_isRejected() {
        // Gemini's responseSchema can't express conditional requires
        // (no if/then/else). The validate() check enforces that any peer_360
        // citation identifies the author — without it the manager can't
        // attribute the feedback.
        ManagerGoldenReview g = valid();
        g.technologyComments = "Strong delivery [c1].";
        ManagerGoldenReview.Citation c = new ManagerGoldenReview.Citation();
        c.source = "peer_360";
        c.reviewId = "r-360-bob";
        c.questionId = "q1";
        c.quote = "shipped on time";
        // authorUserId left null — illegal for peer_360
        g.citations = java.util.Map.of("c1", c);
        assertTrue(g.validate().stream()
                        .anyMatch(e -> e.contains("[c1] peer_360 source requires authorUserId")),
                "peer_360 without authorUserId must be rejected");
    }

    @Test
    void validate_selfWithoutAuthorUserId_isAccepted() {
        // Self citations legitimately omit authorUserId — only peer_360 requires it.
        ManagerGoldenReview g = valid();
        g.technologyComments = "Strong delivery [c1].";
        ManagerGoldenReview.Citation c = new ManagerGoldenReview.Citation();
        c.source = "self";
        c.reviewId = "r-self-1";
        c.questionId = "q1";
        c.quote = "shipped on time";
        // authorUserId intentionally absent — legal for self citations
        g.citations = java.util.Map.of("c1", c);
        assertTrue(g.validate().isEmpty(),
                "self citations should not require authorUserId");
    }

    @Test
    void validate_unreferencedMalformedCitation_isRejected() {
        // RS-2 follow-up (code-review): pre-fix, validate() only iterated [cN]
        // tokens found in prose, so Gemini could emit unreferenced garbage like
        // citations = {"c99": {"source":"peer_360"}} and the row would persist
        // silently. The shape loop now runs across ALL entries, referenced or not.
        ManagerGoldenReview g = valid();
        // No [cN] token anywhere in the comments
        ManagerGoldenReview.Citation orphan = new ManagerGoldenReview.Citation();
        orphan.source = "peer_360";
        // Everything else blank — should be flagged as missing reviewId / questionId / quote / authorUserId
        g.citations = java.util.Map.of("c99", orphan);

        List<String> errors = g.validate();

        assertTrue(errors.stream().anyMatch(e -> e.contains("[c99] reviewId is required")),
                "unreferenced citation must still be shape-validated: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("[c99] questionId is required")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("[c99] quote is required")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("[c99] peer_360 source requires authorUserId")),
                "conditional rule must fire on unreferenced peer_360 entries too: " + errors);
    }

    @Test
    void validate_nullCitationEntryValue_isRejected() {
        // Edge case — Map<String, Citation> can legally hold null values; we must
        // not NPE and we must surface the bad entry.
        ManagerGoldenReview g = valid();
        g.citations = new java.util.HashMap<>();
        g.citations.put("c1", null);

        List<String> errors = g.validate();

        assertTrue(errors.stream().anyMatch(e -> e.contains("[c1] entry is null")),
                "null citation entry must be flagged: " + errors);
    }

    @Test
    void validate_validPeerCitation_returnsNoErrors() {
        ManagerGoldenReview g = valid();
        g.technologyComments = "Strong delivery [c1] and ownership.";
        ManagerGoldenReview.Citation c = new ManagerGoldenReview.Citation();
        c.source = "peer_360";
        c.reviewId = "r-360-bob";
        c.questionId = "q1";
        c.quote = "ownership across projects";
        c.authorUserId = "bob";
        g.citations = java.util.Map.of("c1", c);
        assertTrue(g.validate().isEmpty(),
                "fully-populated peer_360 citation must pass");
    }

    @Test
    void validate_emptyCitationsWithNoTokens_isValid() {
        ManagerGoldenReview g = valid();
        // citations null AND no [cN] tokens in prose — valid
        assertTrue(g.validate().isEmpty());
    }

    @Test
    void schemaInvalidException_carriesErrorsAndRawOutput() {
        SchemaInvalidException ex = new SchemaInvalidException(
                List.of("technologyRating must be 1..5 integer"),
                "{\"technologyRating\":\"four\"}");
        assertEquals(1, ex.getErrors().size());
        assertEquals("{\"technologyRating\":\"four\"}", ex.getRawOutput());
        assertTrue(ex.getMessage().contains("technologyRating"));
    }
}

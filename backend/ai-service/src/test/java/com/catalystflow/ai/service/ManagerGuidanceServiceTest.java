package com.catalystflow.ai.service;

import com.catalystflow.ai.model.QuestionSuggestRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ManagerGuidanceServiceTest {

    @Inject
    ManagerGuidanceService managerGuidanceService;

    @Test
    void generateGoldenReview_mockMode_returnsAllFields() {
        ManagerGuidanceService.GoldenResult result =
                managerGuidanceService.generateGoldenReview("Some review text");

        assertNotNull(result);
        assertNotNull(result.golden);
        assertNotNull(result.golden.technologyRating);
        assertNotNull(result.golden.technologyComments);
        assertNotNull(result.golden.consultingRating);
        assertNotNull(result.golden.consultingComments);
        assertNotNull(result.golden.companyValuesComments);
        assertNotNull(result.golden.developmentOpportunities);
        assertNotNull(result.golden.otherComments);
        assertNotNull(result.golden.overallRating);
        // Mock golden must itself satisfy the schema — guards against drift
        // between the mock fixture and the validation contract.
        assertTrue(result.golden.validate().isEmpty(),
                "Mock golden must pass schema validation");
    }

    @Test
    void suggestForQuestion_mockMode_returnsSuggestion() {
        var req = new com.catalystflow.ai.model.QuestionSuggestRequest();
        req.questionId = "q1";
        req.questionText = "Describe client communication";
        req.managerCurrentAnswer = "I managed stakeholder expectations.";
        req.discipline = "Software & DevOps Engineering";
        req.seniority = "Engineer";
        req.employeeReview = new com.catalystflow.ai.model.QuestionSuggestRequest.EmployeeReview();
        var ans = new com.catalystflow.ai.model.QuestionSuggestRequest.EmployeeAnswer();
        ans.questionId = "q1";
        ans.questionText = "Describe your communication";
        ans.response = "I communicate well.";
        req.employeeReview.answers = java.util.List.of(ans);
        ManagerGuidanceService.SuggestionResult result = managerGuidanceService.suggestForQuestion(
                req, java.util.List.of("criterion a"), java.util.List.of("criterion b"), "{}");

        assertNotNull(result);
        assertNotNull(result.suggestion);
        assertFalse(result.suggestion.isBlank());
        assertNotNull(result.citations);
    }

    @Test
    void buildReviewText_withAnswersAndOverall_includesAllContent() {
        var a1 = new ManagerGuidanceService.AnswerWithText();
        a1.questionId = "q1";
        a1.questionText = "Describe your technical skills";
        a1.response = "I built microservices using Java.";

        String text = managerGuidanceService.buildReviewText(
                List.of(a1), "Strong overall performance.", "4");

        assertTrue(text.contains("Describe your technical skills"));
        assertTrue(text.contains("I built microservices using Java."));
        assertTrue(text.contains("Strong overall performance."));
        assertTrue(text.contains("Rating: 4"));
    }

    @Test
    void buildReviewText_emptyAnswers_returnsOnlyOverall() {
        String text = managerGuidanceService.buildReviewText(
                List.of(), "Average performance.", "3");

        assertTrue(text.contains("Average performance."));
        assertTrue(text.contains("Rating: 3"));
    }

    @Test
    void buildReviewText_blankAnswerResponse_skipsBlankAnswers() {
        var a1 = new ManagerGuidanceService.AnswerWithText();
        a1.questionId = "q1";
        a1.questionText = "Some question";
        a1.response = "   ";

        String text = managerGuidanceService.buildReviewText(List.of(a1), null, null);

        assertFalse(text.contains("Some question"));
    }

    @Test
    void generatePeer360Digest_mockMode_returnsValidDigest() {
        ManagerGuidanceService.DigestResult r =
                managerGuidanceService.generatePeer360Digest("=== Peer review r-1 ===\nQ: Q1\nA: A1\n");

        assertNotNull(r);
        assertNotNull(r.digest);
        assertNotNull(r.digest.themes);
        assertNotNull(r.digest.consensus);
        assertNotNull(r.digest.dissent);
        assertNotNull(r.digest.overallSentiment);
        assertTrue(r.digest.validate().isEmpty(),
                "Mock peer-360 digest must pass schema validation");
    }

    @Test
    void buildPeer360Corpus_multipleBlocks_fencedWithReviewIds() {
        ManagerGuidanceService.Peer360ReviewBlock b1 = new ManagerGuidanceService.Peer360ReviewBlock();
        b1.reviewId = "r-360-bob";
        var qa1 = new ManagerGuidanceService.AnswerWithText();
        qa1.questionId = "q1";
        qa1.questionText = "Strengths?";
        qa1.response = "Strong delivery leadership.";
        b1.answers = List.of(qa1);

        ManagerGuidanceService.Peer360ReviewBlock b2 = new ManagerGuidanceService.Peer360ReviewBlock();
        b2.reviewId = "r-360-cy";
        var qa2 = new ManagerGuidanceService.AnswerWithText();
        qa2.questionId = "q1";
        qa2.questionText = "Strengths?";
        qa2.response = "Excellent stakeholder management.";
        b2.answers = List.of(qa2);

        String corpus = managerGuidanceService.buildPeer360Corpus(List.of(b1, b2));

        assertTrue(corpus.contains("=== Peer review r-360-bob ==="),
                "first block must be fenced with its reviewId");
        assertTrue(corpus.contains("=== Peer review r-360-cy ==="),
                "second block must be fenced with its reviewId");
        assertTrue(corpus.contains("Strong delivery leadership."));
        assertTrue(corpus.contains("Excellent stakeholder management."));
    }

    @Test
    void buildPeer360Corpus_blankResponse_skipsAnswer() {
        ManagerGuidanceService.Peer360ReviewBlock b = new ManagerGuidanceService.Peer360ReviewBlock();
        b.reviewId = "r-360-x";
        var qa = new ManagerGuidanceService.AnswerWithText();
        qa.questionId = "q1";
        qa.questionText = "Strengths?";
        qa.response = "   ";
        b.answers = List.of(qa);

        String corpus = managerGuidanceService.buildPeer360Corpus(List.of(b));

        // Header still present, but blank Q/A is dropped — keeps Gemini prompt
        // clean of empty placeholders.
        assertTrue(corpus.contains("=== Peer review r-360-x ==="));
        assertFalse(corpus.contains("Strengths?"));
    }

    @Test
    void buildPeer360Corpus_emptyList_returnsEmptyString() {
        String corpus = managerGuidanceService.buildPeer360Corpus(List.of());
        assertEquals("", corpus);
    }

    @Test
    void validateSuggestion_groupedCitationToken_isRejected() throws Exception {
        ManagerGuidanceService.SuggestionResult r = new ManagerGuidanceService.SuggestionResult();
        r.isGoodAnswer = false;
        r.suggestion = "Needs stronger example [c1, c2].";
        r.citations = java.util.Map.of();

        List<String> errors = validate(r);

        assertTrue(errors.stream().anyMatch(e -> e.contains("invalid citation token format")),
                "grouped citation tokens must be rejected");
    }

    @Test
    void validateSuggestion_separateMultipleCitationTokens_passes() throws Exception {
        ManagerGuidanceService.SuggestionResult r = new ManagerGuidanceService.SuggestionResult();
        r.isGoodAnswer = false;
        r.suggestion = "Add specifics [c1][c2].";
        r.citations = java.util.Map.of(
                "c1", citation("q1", "example"),
                "c2", citation("q2", "second example"));

        List<String> errors = validate(r);

        assertTrue(errors.isEmpty(), "separate strict [cN] tokens with matching citations should pass");
    }

    @Test
    void validateSuggestion_unknownSelfQuestionId_isRejected() throws Exception {
        ManagerGuidanceService.SuggestionResult r = new ManagerGuidanceService.SuggestionResult();
        r.isGoodAnswer = false;
        r.suggestion = "Add specifics [c1].";
        r.citations = java.util.Map.of("c1", citation("not-an-allowed-id", "example"));

        QuestionSuggestRequest req = requestWithAnswer("q1", "Question one", "Answer one");

        List<String> errors = validate(r, req);

        assertTrue(errors.stream().anyMatch(e -> e.contains("not one of allowed employee answer IDs")),
                "unknown self citation questionId should be rejected");
    }

    @Test
    void buildQuestionSuggestPrompt_containsAllowedIdsAndNoFakeIds() throws Exception {
        QuestionSuggestRequest req = requestWithAnswer("q1", "Consulting question", "I worked with stakeholders.");
        req.questionId = "manager-q";
        req.questionText = "Manager consulting assessment";
        req.managerCurrentAnswer = "short answer";
        req.discipline = "Software & DevOps Engineering";
        req.seniority = "Analyst";

        String prompt = buildPrompt(req);

        assertTrue(prompt.contains("Never group tokens in one bracket"),
                "prompt must explicitly ban grouped citation tokens");
        assertTrue(prompt.contains("ALLOWED SELF CITATION QUESTION IDS"),
                "prompt must list allowed self citation IDs");
        assertTrue(prompt.contains("- q1: Consulting question"),
                "prompt must include allowed self citation IDs with question text");
        // §2.5 slim-down removed the "Return ONLY valid JSON: {…}" block and
        // the "Example valid JSON: {…}" block — responseSchema enforces shape
        // server-side so the in-prompt examples were dead weight. Assertions
        // testing for those substrings are correspondingly dropped.
        assertFalse(prompt.contains("Example valid JSON"),
                "Slim-down §2.5: no more JSON example block in the prompt");
        assertFalse(prompt.contains("example-question-id"),
                "prompt must not include copyable fake citation IDs");
    }

    // buildQuestionSuggestRepairPrompt_containsAllowedIdsAndStrictCitationRules
    // removed in RS-4 — the helper it tested (buildQuestionSuggestRepairPrompt)
    // was deleted along with the question-suggest repair loop. Schema enforcement
    // happens at the Gemini layer now (ResponseSchemas.schemaForQuestionSuggest);
    // any residual schema-invalid response throws SchemaInvalidException on the
    // first call and is verified by ManagerGuidanceServiceQuestionSuggestSchemaTest.

    @Test
    void redactedPreview_redactsIdsAndTruncates() throws Exception {
        Method m = ManagerGuidanceService.class.getDeclaredMethod("redactedPreview", String.class);
        m.setAccessible(true);
        String preview = (String) m.invoke(null,
                "id 0571b5eb-fb5a-44a9-8c2f-bc8cbcee40f1 email person@example.com "
                        + "x".repeat(600));

        assertFalse(preview.contains("0571b5eb-fb5a-44a9-8c2f-bc8cbcee40f1"));
        assertFalse(preview.contains("person@example.com"));
        assertTrue(preview.contains("[uuid]"));
        assertTrue(preview.contains("[email]"));
        assertTrue(preview.length() <= 503);
    }

    private List<String> validate(ManagerGuidanceService.SuggestionResult r) throws Exception {
        Method m = ManagerGuidanceService.class.getDeclaredMethod("validateSuggestion",
                ManagerGuidanceService.SuggestionResult.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) m.invoke(managerGuidanceService, r);
        return errors;
    }

    private List<String> validate(ManagerGuidanceService.SuggestionResult r,
                                  QuestionSuggestRequest req) throws Exception {
        Method m = ManagerGuidanceService.class.getDeclaredMethod("validateSuggestion",
                ManagerGuidanceService.SuggestionResult.class,
                QuestionSuggestRequest.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) m.invoke(managerGuidanceService, r, req);
        return errors;
    }

    private String buildPrompt(QuestionSuggestRequest req) throws Exception {
        Method m = ManagerGuidanceService.class.getDeclaredMethod(
                "buildQuestionSuggestPrompt",
                QuestionSuggestRequest.class,
                java.util.List.class,
                java.util.List.class,
                String.class);
        m.setAccessible(true);
        return (String) m.invoke(managerGuidanceService, req,
                List.of("tech criterion"), List.of("consulting criterion"), "{}");
    }

    private ManagerGuidanceService.SuggestionCitation citation(String questionId, String quote) {
        ManagerGuidanceService.SuggestionCitation c = new ManagerGuidanceService.SuggestionCitation();
        c.source = "self";
        c.questionId = questionId;
        c.quote = quote;
        return c;
    }

    private QuestionSuggestRequest requestWithAnswer(String questionId, String questionText, String response) {
        QuestionSuggestRequest req = new QuestionSuggestRequest();
        req.employeeReview = new QuestionSuggestRequest.EmployeeReview();
        QuestionSuggestRequest.EmployeeAnswer ans = new QuestionSuggestRequest.EmployeeAnswer();
        ans.questionId = questionId;
        ans.questionText = questionText;
        ans.response = response;
        req.employeeReview.answers = java.util.List.of(ans);
        return req;
    }
}

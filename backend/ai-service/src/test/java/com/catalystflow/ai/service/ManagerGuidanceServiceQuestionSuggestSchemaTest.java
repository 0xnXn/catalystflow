package com.catalystflow.ai.service;

import com.catalystflow.ai.model.QuestionSuggestRequest;
import com.catalystflow.ai.model.SchemaInvalidException;
import com.catalystflow.ai.schema.ResponseSchemas;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RS-4 — verifies the {@code MANAGER_QUESTION_SUGGEST} path uses Gemini's
 * {@code responseSchema} and that:
 * <ol>
 *   <li>The validate→repair→validate→throw loop is deleted (mirror of RS-2 / RS-3).</li>
 *   <li>The broad {@code catch(Exception)} that returned the
 *       {@code "AI suggestion temporarily unavailable"} stub is deleted.
 *       Exceptions propagate to
 *       {@code runJobInProcess} which handles classification + FAILED row.</li>
 * </ol>
 *
 * <p>Pure unit test, manual field wiring — same pattern as the GOLDEN +
 * DIGEST counterparts.
 */
class ManagerGuidanceServiceQuestionSuggestSchemaTest {

    private ManagerGuidanceService svc;
    private GeminiClient geminiClient;

    @BeforeEach
    void setup() {
        geminiClient = mock(GeminiClient.class);
        svc = new ManagerGuidanceService();
        svc.mockMode = false;
        svc.mapper = new ObjectMapper();
        svc.geminiClient = geminiClient;
        svc.modelResolver = ModelResolver.allTasksUseModel("gemini-test");
        // CC-4a: default OFF — production default. Specific tests opt in.
        svc.questionSuggestCacheEnabled = false;
    }

    // ── AC-4.2: responseSchema is actually sent ──────────────────────────────

    @Test
    void suggestForQuestion_passesResponseSchemaToGemini() {
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validSuggestionJson()));

        svc.suggestForQuestion(request(), List.of("tech"), List.of("consulting"), "{}");

        ArgumentCaptor<GeminiRequest> reqCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(1)).call(reqCaptor.capture());
        GeminiRequest captured = reqCaptor.getValue();

        assertNotNull(captured.responseSchema(),
                "RS-4: responseSchema must be populated on the GeminiRequest");
        assertEquals("manager-question-suggest", captured.taskName());
        assertEquals(ResponseSchemas.schemaForQuestionSuggest(), captured.responseSchema(),
                "Schema must come from ResponseSchemas.schemaForQuestionSuggest()");
    }

    // ── AC-4.3 / AC-4.4: Open Bug fix — SchemaInvalidException PROPAGATES ────

    @Test
    void questionSuggest_schemaInvalid_propagates_noStubFallback() {
        // Pre-RS-4: a self citation without questionId (or any other
        // validateSuggestion failure) was swallowed by catch(Exception) into
        // a stub SuggestionResult — the manager saw "AI suggestion temporarily
        // unavailable" but the job was marked COMPLETED. Schema failures were
        // invisible to ops dashboards.
        //
        // Post-RS-4: the exception propagates to runJobInProcess which writes
        // the AiJobs row as FAILED with SCHEMA_INVALID + token telemetry.
        // Frontend renders the ADR-5 fallback like every other manager flow.
        String badJson = "{"
                + "\"isGoodAnswer\":false,"
                + "\"suggestion\":\"Add specifics [c1].\","
                + "\"citations\":{"
                + "  \"c1\":{\"source\":\"self\",\"quote\":\"some quote\"}"
                + "}"
                + "}";
        // Citation references token [c1] but lacks questionId for source=self —
        // validateSuggestion rejects per the source-conditional rule
        // (responseSchema can't express conditional requires).
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(badJson));

        SchemaInvalidException ex = assertThrows(
                SchemaInvalidException.class,
                () -> svc.suggestForQuestion(request(), List.of("tech"), List.of("consulting"), "{}"),
                "Schema-invalid response must throw — NOT return the legacy stub SuggestionResult");

        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("questionId")),
                "Error must cite the missing source-conditional field: " + ex.getErrors());
        verify(geminiClient, times(1)).call(any(GeminiRequest.class));
    }

    @Test
    void questionSuggest_unparseableJson_propagates_noStubFallback() {
        // Pre-RS-4 the broad catch turned this into a stub too. Post-RS-4 the
        // exception escapes — JSON parse failure becomes a real signal.
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult("not json at all"));

        SchemaInvalidException ex = assertThrows(
                SchemaInvalidException.class,
                () -> svc.suggestForQuestion(request(), List.of("tech"), List.of("consulting"), "{}"));

        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("not valid JSON")),
                "Unparseable response should carry the parse-failure marker: " + ex.getErrors());
    }

    // ── Telemetry on exception (RS-2 follow-up pattern, applied to RS-4) ──────

    @Test
    void questionSuggest_schemaInvalid_carriesTelemetryOnException() {
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(new GeminiResult("{\"bogus\":true}", 150, 40, "STOP", "gemini-test", false, null));

        SchemaInvalidException ex = assertThrows(
                SchemaInvalidException.class,
                () -> svc.suggestForQuestion(request(), List.of("tech"), List.of("consulting"), "{}"));

        assertEquals(Integer.valueOf(150), ex.tokenIn,
                "tokenIn must propagate so AiJobs FAILED row reflects prompt cost");
        assertEquals(Integer.valueOf(40), ex.tokenOut);
        assertEquals(Integer.valueOf(0), ex.repairRetries,
                "repairRetries must be 0 — non-zero would mean the repair loop regressed");
        assertEquals("gemini-test", ex.modelUsed);
    }

    // ── Success path: repairRetries pinned to 0, citations preserved ─────────

    @Test
    void suggestForQuestion_success_repairRetriesIsZero() {
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validSuggestionJson()));

        ManagerGuidanceService.SuggestionResult result =
                svc.suggestForQuestion(request(), List.of("tech"), List.of("consulting"), "{}");

        assertNotNull(result);
        assertNotNull(result.suggestion);
        assertFalse(result.suggestion.isBlank());
        assertEquals(Integer.valueOf(0), result.repairRetries,
                "Post-RS-4 every successful call must report repairRetries=0");
        assertEquals(200, result.tokenIn);
        assertEquals(70, result.tokenOut);
    }

    // ── RS-4 follow-up regression: isGoodAnswer silent default ───────────────

    @Test
    void questionSuggest_missingIsGoodAnswer_isRejected() {
        // Pre-fix: SuggestionResult.isGoodAnswer was primitive boolean. Jackson
        // mapped a missing JSON field to false (the default) and validateSuggestion
        // never checked it. So {"suggestion":"ok","citations":{}} would deserialize
        // as isGoodAnswer=false and pass validation — undermining AC-4.1.
        //
        // Post-fix: isGoodAnswer is Boolean. Missing field → null. validateSuggestion
        // rejects null. responseSchema declares the field required at the Gemini
        // layer too, but this is the defensive last line.
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult("{\"suggestion\":\"ok\",\"citations\":{}}"));

        SchemaInvalidException ex = assertThrows(
                SchemaInvalidException.class,
                () -> svc.suggestForQuestion(request(), List.of("tech"), List.of("consulting"), "{}"),
                "Missing isGoodAnswer must throw, not silently default to false");

        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("isGoodAnswer is required")),
                "Error must explicitly cite isGoodAnswer: " + ex.getErrors());
    }

    // ── Regression: legacy stub message MUST NOT appear in any failure path ──

    @Test
    void questionSuggest_failure_neverReturnsLegacyStubMessage() {
        // Belt-and-braces: even with a schema-invalid payload, the legacy
        // "AI suggestion temporarily unavailable" stub must never surface.
        // If it does, the Open Bug regressed.
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult("{}"));

        SchemaInvalidException ex = assertThrows(
                SchemaInvalidException.class,
                () -> svc.suggestForQuestion(request(), List.of("tech"), List.of("consulting"), "{}"));

        assertFalse(ex.getMessage().contains("AI suggestion temporarily unavailable"),
                "The legacy stub message must never appear — the swallow-everything catch was deleted in RS-4");
    }

    // ── CC-4a: per-task cache enablement ─────────────────────────────────────

    @Test
    void suggestForQuestion_cacheFlagOff_sendsNoCacheRequest_promptShapeUnchanged() {
        // Default behaviour (gemini-cache.enabled.question-suggest=false).
        // GeminiRequest has cacheRequest=null; userPrompt contains the FULL
        // combined prompt (cacheable prefix + dynamic part) — same shape as
        // pre-CC-4a. AC: per-task flag default OFF prevents accidental cache
        // activation; production opts in via SAM env var flip.
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validSuggestionJson()));

        svc.suggestForQuestion(request(), List.of("tech criterion"), List.of("consulting criterion"), "{}");

        ArgumentCaptor<GeminiRequest> reqCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(1)).call(reqCaptor.capture());
        GeminiRequest captured = reqCaptor.getValue();

        org.junit.jupiter.api.Assertions.assertNull(captured.cacheRequest(),
                "Flag OFF: cacheRequest must be null so GeminiClient skips cache path");
        assertTrue(captured.userPrompt().contains("ROLE EXPECTATIONS"),
                "Flag OFF: userPrompt must include the static prefix portion: "
                        + captured.userPrompt().substring(0, Math.min(200, captured.userPrompt().length())));
        assertTrue(captured.userPrompt().contains("QUESTION CONTEXT"),
                "Flag OFF: userPrompt must include the dynamic portion");
    }

    @Test
    void suggestForQuestion_cacheFlagOn_attachesCacheRequest_userPromptIsDynamicOnly() {
        // CC-4a: when the flag is on, suggestForQuestion must construct a
        // CacheRequest with the static prefix and pass only the dynamic
        // portion as userPrompt. GeminiClient sees cacheRequest != null and
        // calls cacheService.resolveOrCreate before building the body.
        svc.questionSuggestCacheEnabled = true;
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validSuggestionJson()));

        svc.suggestForQuestion(request(), List.of("tech criterion"), List.of("consulting criterion"), "{}");

        ArgumentCaptor<GeminiRequest> reqCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(1)).call(reqCaptor.capture());
        GeminiRequest captured = reqCaptor.getValue();

        assertNotNull(captured.cacheRequest(),
                "Flag ON: cacheRequest must be populated for GeminiClient to consult the cache");
        CacheRequest cr = captured.cacheRequest();
        assertEquals("MANAGER_QUESTION_SUGGEST", cr.taskType());
        assertEquals("QUESTION_SUGGEST_V3", cr.promptVersion(),
                "CC-4a follow-up bumped V2 → V3 to invalidate cache rows from the "
                        + "broken body shape + carry the citation-rule rewording");
        assertEquals("Software & DevOps Engineering", cr.discipline(),
                "discipline keys the cache row — different discipline → different cache");
        assertEquals("Engineer", cr.seniority());
        assertEquals(3600L, cr.ttlSeconds(),
                "§D5: QUESTION_SUGGEST uses 1h TTL (highest-reuse task)");
        assertEquals("gemini-test", cr.model(),
                "model must come from configured gemini.model");
        // The cacheable prefix carries the criteria + style + task instructions.
        assertTrue(cr.extraPrefix().contains("ROLE EXPECTATIONS"),
                "extraPrefix must include the criteria block: "
                        + cr.extraPrefix().substring(0, Math.min(200, cr.extraPrefix().length())));
        assertTrue(cr.extraPrefix().contains("Never group tokens in one bracket"),
                "extraPrefix must include the TASK + citation rules block");

        // The userPrompt now contains ONLY the dynamic portion — proves the
        // split actually happened. ROLE EXPECTATIONS must NOT appear in
        // userPrompt when caching is on (it's in the cached prefix).
        assertTrue(captured.userPrompt().contains("QUESTION CONTEXT"),
                "Flag ON: userPrompt has the dynamic portion (QUESTION CONTEXT)");
        org.junit.jupiter.api.Assertions.assertFalse(
                captured.userPrompt().contains("ROLE EXPECTATIONS"),
                "Flag ON: userPrompt must NOT include the cached prefix portion");
    }

    @Test
    void buildQuestionSuggestCacheablePrefix_containsNoDirectionalWords_anywhere() {
        // CC-4a follow-up round-3 MEDIUM: the static prefix MUST NOT contain
        // "above" or "below" ANYWHERE — not just near the citation rule.
        //
        // Pre-fix the prompt embedded a "Do NOT use directional words like
        // 'above' or 'below'" instruction that itself contained both banned
        // words. The previous test only scanned a 200-char window around the
        // source=self rule, so the leak slipped past. Round-3 widens the
        // scope to the whole prefix.
        //
        // Test setup uses minimal placeholder criteria + empty style JSON so
        // there's no risk of false-positives from data: any "above"/"below"
        // in the prefix is necessarily from the static prompt text. In
        // production, criteria/style come from arbitrary admin input — those
        // are DATA (the cached content), not INSTRUCTIONS, so directional
        // words there don't confuse the model the way they would in
        // boilerplate. Asserting on the test-controlled placeholder shape
        // therefore catches instructional regressions without forbidding
        // legitimate user data.
        //
        // Background — why the rule went neutral in the first place:
        // the source=self citation rule references the ALLOWED SELF CITATION
        // QUESTION IDS block. That block's position relative to the rule
        // differs by transport. Cache-ON sends this prefix first and the
        // ALLOWED block follows → "below". Cache-OFF inlines the prefix
        // after the dynamic half → "above". Both wordings lie on one path,
        // so neutral wording ("in this request") is the only safe form.
        String prefix = svc.buildQuestionSuggestCacheablePrefix(
                "DevOps", "Engineer",
                List.of("tech"), List.of("consulting"), "{}");

        assertFalse(prefix.contains("above"),
                "Prefix MUST NOT contain 'above' anywhere — in cache-ON mode "
                        + "the ALLOWED block appears AFTER this rule. Full prefix:\n" + prefix);
        assertFalse(prefix.contains("below"),
                "Prefix MUST NOT contain 'below' anywhere — in cache-OFF mode "
                        + "the rule appears AFTER the ALLOWED block. Full prefix:\n" + prefix);

        // Positive: the source=self rule must still reference the ALLOWED
        // block with neutral wording. Normalise whitespace because the rule
        // text wraps across lines in the source text block.
        int ruleIdx = prefix.indexOf("source=self");
        org.junit.jupiter.api.Assertions.assertNotEquals(-1, ruleIdx,
                "Prefix must contain the source=self citation rule");
        String normalised = prefix.replaceAll("\\s+", " ");
        assertTrue(normalised.contains("ALLOWED SELF CITATION QUESTION IDS block in this request")
                        || normalised.contains("ALLOWED SELF CITATION QUESTION IDS block in the request"),
                "Rule must reference the ALLOWED block with neutral 'in this request' wording. "
                        + "Got (whitespace-normalised, abbreviated): "
                        + normalised.substring(Math.max(0, normalised.indexOf("source=self") - 20),
                                Math.min(normalised.length(), normalised.indexOf("source=self") + 200)));
    }

    @Test
    void suggestForQuestion_cacheFlagOff_userPromptEqualsDynamicThenPrefix_preCC4aByteOrder() {
        // Regression guard for the pre-CC-4a byte order. The cache-OFF path is
        // the production default; it MUST be byte-identical to what
        // suggestForQuestion sent before CC-4a or we silently change the
        // prompt for every manager call under the flag.
        //
        // Pre-CC-4a buildQuestionSuggestPrompt emitted dynamic-first then
        // static (ROLE / STYLE / TASK / citation rules). The CC-4a follow-up
        // restored this exact order after a regression that flipped it to
        // prefix+dynamic. Pin it here so any future edit that re-flips the
        // halves trips this test instead of changing Gemini's input silently.
        //
        // Reviewer note (CC-4a MEDIUM #1): the previous version of this test
        // asserted prefix+dynamic — that "blessed" the regression instead of
        // catching it. The correct invariant is dynamic+prefix.
        QuestionSuggestRequest req = request();
        List<String> tech = List.of("tech criterion");
        List<String> consulting = List.of("consulting criterion");
        String style = "{}";

        String prefix = svc.buildQuestionSuggestCacheablePrefix(req.discipline, req.seniority,
                tech, consulting, style);
        String dynamic = svc.buildQuestionSuggestDynamicPart(req);

        svc.questionSuggestCacheEnabled = false;
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validSuggestionJson()));
        svc.suggestForQuestion(req, tech, consulting, style);
        ArgumentCaptor<GeminiRequest> flagOffCap = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(1)).call(flagOffCap.capture());
        String flagOffPrompt = flagOffCap.getValue().userPrompt();

        assertEquals(dynamic + prefix, flagOffPrompt,
                "Cache-OFF userPrompt must equal dynamic-part + cacheable-prefix "
                        + "(pre-CC-4a byte order). If this fails the order was flipped "
                        + "and Gemini is now seeing a different prompt under the default flag.");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static GeminiResult geminiResult(String text) {
        return new GeminiResult(text, 200, 70, "STOP", "gemini-test", false, null);
    }

    /** A schema-valid SuggestionResult JSON (passes validateSuggestion). */
    private static String validSuggestionJson() {
        return "{"
                + "\"isGoodAnswer\":true,"
                + "\"suggestion\":\"Strong stakeholder example with measurable outcome.\","
                + "\"citations\":{}"
                + "}";
    }

    /** Minimal QuestionSuggestRequest with one allowed self answer. */
    private static QuestionSuggestRequest request() {
        QuestionSuggestRequest req = new QuestionSuggestRequest();
        req.questionId = "mgr-q1";
        req.questionText = "How did the employee handle stakeholders?";
        req.managerCurrentAnswer = "Worked well with the team.";
        req.discipline = "Software & DevOps Engineering";
        req.seniority = "Engineer";
        req.employeeReview = new QuestionSuggestRequest.EmployeeReview();
        QuestionSuggestRequest.EmployeeAnswer ans = new QuestionSuggestRequest.EmployeeAnswer();
        ans.questionId = "q1";
        ans.questionText = "Stakeholder example";
        ans.response = "I worked closely with PMs and clients on the rollout.";
        req.employeeReview.answers = List.of(ans);
        return req;
    }
}

package com.catalystflow.ai.service;

import com.catalystflow.ai.model.ManagerGoldenReview;
import com.catalystflow.ai.model.SchemaInvalidException;
import com.catalystflow.ai.schema.ResponseSchemas;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RS-2 — verifies the {@code MANAGER_GOLDEN} path uses Gemini's
 * {@code responseSchema} and that the {@code validate→repair→validate→throw}
 * loop has been deleted.
 *
 * <p>Pure unit test (no {@code @QuarkusTest}): instantiates
 * {@link ManagerGuidanceService} directly and wires the package-private
 * {@code mockMode}, {@code mapper}, and {@code geminiClient} fields by hand.
 * This sidesteps the test-resources {@code coaching.mock=true} default and
 * the Quarkus bootstrap overhead — both unnecessary for asserting
 * delegation + exception flow.
 *
 * <p>The Quarkus-managed {@link ManagerGuidanceServiceTest} stays in mock
 * mode and asserts the mock fixture; this class covers the real-call shape.
 */
class ManagerGuidanceServiceGoldenSchemaTest {

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
        // CC-4b: default OFF — production default. Specific tests opt in.
        svc.goldenCacheEnabled = false;
        // Sibling flag also defaulted so the SUT field-init contract is
        // explicit even though this test class doesn't exercise digest.
        svc.digestCacheEnabled = false;
    }

    // ── AC-2.2: responseSchema is actually sent ──────────────────────────────

    @Test
    void generateGoldenReview_passesResponseSchemaToGemini() {
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validGoldenJson()));

        svc.generateGoldenReview("manager draft text");

        ArgumentCaptor<GeminiRequest> reqCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(1)).call(reqCaptor.capture());
        GeminiRequest captured = reqCaptor.getValue();

        assertNotNull(captured.responseSchema(),
                "RS-2: responseSchema must be populated on the GeminiRequest");
        assertEquals("manager-golden", captured.taskName());
        assertEquals(ResponseSchemas.schemaForManagerGolden(), captured.responseSchema(),
                "Schema must come from ResponseSchemas.schemaForManagerGolden() — single source of truth");
    }

    // ── AC-2.5: SchemaInvalidException thrown after ONE call, no repair retry ──

    @Test
    void golden_responseSchema_enforced() {
        // Gemini returns JSON that parses but fails ManagerGoldenReview.validate()
        // (missing technologyRating + technologyComments). Pre-RS-2 this would
        // have triggered a SECOND Gemini call with GOLDEN_REPAIR_PROMPT; post-RS-2
        // it must throw immediately because the repair loop is deleted.
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult("{\"consultingRating\":3,"
                        + "\"consultingComments\":\"x\","
                        + "\"companyValuesComments\":\"x\","
                        + "\"developmentOpportunities\":\"x\","
                        + "\"otherComments\":\"x\","
                        + "\"overallRating\":3}"));

        SchemaInvalidException ex = assertThrows(
                SchemaInvalidException.class,
                () -> svc.generateGoldenReview("manager draft text"),
                "Schema-invalid response must throw immediately; no repair retry");

        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("technologyRating")),
                "Validation errors must cite the missing field: " + ex.getErrors());
        verify(geminiClient, times(1)).call(any(GeminiRequest.class));
    }

    // ── RS-2 follow-up (code-review): telemetry must travel on the exception ──

    @Test
    void golden_responseSchema_enforced_carriesTelemetryOnException() {
        // Pre-fix the GoldenResult.tokenIn/tokenOut/repairRetries were set on
        // the wrapper but the wrapper was discarded when the method threw, so
        // the AiReviewService catch block persisted updateFailed(..., null,
        // null, ...) — failed-job rows had no cost attribution and no
        // repair-retry signal. The exception now carries them.
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(new GeminiResult("{\"not\":\"valid\"}", 250, 80, "STOP", "gemini-test", false, null));

        SchemaInvalidException ex = assertThrows(
                SchemaInvalidException.class,
                () -> svc.generateGoldenReview("manager draft text"));

        assertEquals(Integer.valueOf(250), ex.tokenIn,
                "tokenIn must propagate so AiJobs row attributes the failed call's prompt cost");
        assertEquals(Integer.valueOf(80), ex.tokenOut,
                "tokenOut must propagate so AiJobs row attributes the failed call's output cost");
        assertEquals(Integer.valueOf(0), ex.repairRetries,
                "retries disabled here (hand-wired SUT leaves schemaInvalidMaxRetries=0), so the "
                        + "terminal exception spent 0 retries; the bounded-retry path is covered by "
                        + "generateGoldenReview_allAttemptsInvalid_throwsAfterBoundedRetries");
        assertEquals("gemini-test", ex.modelUsed,
                "modelUsed must propagate so failure rate can be sliced by model in dashboards");
    }

    @Test
    void golden_unparseableJson_throwsImmediately_noRetry() {
        // Belt-and-braces: if Gemini somehow returns non-JSON despite
        // responseSchema (e.g. an upstream model fault), we still throw
        // immediately rather than retry. Pre-RS-2 this hit the repair loop.
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult("totally not json"));

        SchemaInvalidException ex = assertThrows(
                SchemaInvalidException.class,
                () -> svc.generateGoldenReview("manager draft text"));

        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("not valid JSON")),
                "Unparseable response should carry the parse-failure marker: " + ex.getErrors());
        verify(geminiClient, times(1)).call(any(GeminiRequest.class));
    }

    // ── AC-2.4: success path persists repairRetries=0 (never null, never 1) ──

    @Test
    void generateGoldenReview_success_repairRetriesIsZero() {
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validGoldenJson()));

        ManagerGuidanceService.GoldenResult result = svc.generateGoldenReview("manager draft text");

        assertNotNull(result.golden);
        assertEquals(Integer.valueOf(0), result.repairRetries,
                "Post-RS-2 every successful call must report repairRetries=0; "
                + "a non-zero value would mean the repair loop regressed back into existence");
        // Token telemetry preserved from the GeminiResult
        assertEquals(100, result.tokenIn);
        assertEquals(50, result.tokenOut);
    }

    // ── CC-4b: per-task cache enablement (GOLDEN) ────────────────────────────

    @Test
    void generateGoldenReview_cacheFlagOff_sendsNoCacheRequest() {
        // Default behaviour (gemini-cache.enabled.golden=false). GeminiRequest
        // has cacheRequest=null; the request body shape is unchanged from
        // RS-2. AC: per-task flag default OFF prevents accidental cache
        // activation; production opts in via SAM env var flip.
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validGoldenJson()));

        svc.generateGoldenReview("manager draft text");

        ArgumentCaptor<GeminiRequest> reqCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(1)).call(reqCaptor.capture());
        GeminiRequest captured = reqCaptor.getValue();

        org.junit.jupiter.api.Assertions.assertNull(captured.cacheRequest(),
                "Flag OFF: cacheRequest must be null so GeminiClient skips the cache path");
        org.junit.jupiter.api.Assertions.assertNull(captured.uncachedPrompt(),
                "Flag OFF: uncachedPrompt must be null when there's no cacheRequest "
                        + "(canonical constructor invariant — null is the harmless plain-uncached default)");
    }

    @Test
    void generateGoldenReview_cacheFlagOn_attachesCacheRequest_correctShape() {
        // AC-10.3: when the flag is on, generateGoldenReview must construct a
        // CacheRequest with:
        //  - taskType    = MANAGER_GOLDEN
        //  - promptVersion = MANAGER_GOLDEN_V*  (current from TaskMetadata)
        //  - discipline  = null  (no discipline split — universal prompt)
        //  - seniority   = null  (same reason)
        //  - systemPrompt = GOLDEN_REVIEW_PROMPT
        //  - extraPrefix = ""     (no static user-role prefix; corpus is per-job)
        //  - model       = configured gemini.model
        //  - ttl         = 600s   (§D5: 10 min — one-shot per review)
        svc.goldenCacheEnabled = true;
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validGoldenJson()));

        svc.generateGoldenReview("manager draft text");

        ArgumentCaptor<GeminiRequest> reqCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(1)).call(reqCaptor.capture());
        GeminiRequest captured = reqCaptor.getValue();

        assertNotNull(captured.cacheRequest(),
                "Flag ON: cacheRequest must be populated for GeminiClient to consult the cache");
        CacheRequest cr = captured.cacheRequest();
        assertEquals(TaskMetadata.TASK_MANAGER_GOLDEN, cr.taskType());
        assertEquals(TaskMetadata.PROMPT_VERSION_MANAGER_GOLDEN, cr.promptVersion());
        org.junit.jupiter.api.Assertions.assertNull(cr.discipline(),
                "GOLDEN has no per-discipline cache split — universal prompt");
        org.junit.jupiter.api.Assertions.assertNull(cr.seniority(),
                "GOLDEN has no per-seniority cache split — universal prompt");
        assertEquals("gemini-test", cr.model(),
                "model must come from configured gemini.model");
        assertEquals(600L, cr.ttlSeconds(),
                "§D5: GOLDEN uses 10-min TTL (one-shot per review; corpus is per-job)");
        assertEquals("", cr.extraPrefix(),
                "GOLDEN caches the system prompt only — no static user-role prefix. "
                        + "Empty extraPrefix tells GeminiCacheTransport to omit the contents block.");
        // Round-3 follow-up LOW: cross-equality between cr.systemPrompt() and
        // captured.systemPrompt(). Pins the invariant that the cached content
        // matches what's actually being sent to Gemini — without this a typo
        // could cache the wrong static prompt while every other assertion
        // (task, version, model, TTL, empty prefix) still passes. Cheap one-line
        // guard against a silent-correctness regression.
        assertEquals(captured.systemPrompt(), cr.systemPrompt(),
                "CacheRequest.systemPrompt MUST equal the request's systemPrompt — "
                        + "otherwise the cache row holds different bytes than Gemini receives.");
    }

    @Test
    void generateGoldenReview_cacheFlagOn_uncachedPromptEqualsUserPrompt_noByteOrderDrift() {
        // Regression guard: GOLDEN has no static user-role split, so the
        // cache-miss fallback prompt MUST be byte-identical to the cache-on
        // userPrompt (both are just the per-job text). If a future edit adds
        // a static user-role preamble without updating uncachedPrompt, this
        // assertion fails — same trap shape as the CC-4a CRITICAL #1 bug.
        svc.goldenCacheEnabled = true;
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validGoldenJson()));

        svc.generateGoldenReview("some draft text the manager typed");

        ArgumentCaptor<GeminiRequest> reqCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(1)).call(reqCaptor.capture());
        GeminiRequest captured = reqCaptor.getValue();

        assertEquals(captured.userPrompt(), captured.uncachedPrompt(),
                "GOLDEN: uncachedPrompt must equal userPrompt because there's no "
                        + "static user-role preamble to reconstruct on cache miss. "
                        + "If this drifts, the cache-miss fallback sends a different prompt "
                        + "than the cache-OFF path — silent quality regression.");
    }

    // ── Bounded regeneration retry on non-schema-enforceable validate() rules ──
    //    (2026-06-10: incident — MANAGER_GOLDEN job 8fc123c3 FAILED SCHEMA_INVALID
    //     because gemini-2.5-flash emitted peer_360 citations without authorUserId;
    //     a manual re-trigger (e48ce1f4) cleared it. These tests pin the automatic
    //     bounded re-generation that absorbs that non-determinism.)

    /** A body that parses + passes shape but fails validate(): peer_360 citation lacks authorUserId. */
    private static String peer360MissingAuthorJson() {
        return "{"
                + "\"technologyRating\":4,"
                + "\"technologyComments\":\"Strong technical work [c1].\","
                + "\"consultingRating\":3,"
                + "\"consultingComments\":\"Effective stakeholder management.\","
                + "\"companyValuesComments\":\"Demonstrates company values.\","
                + "\"developmentOpportunities\":\"Grow executive communication.\","
                + "\"otherComments\":\"Solid overall performance.\","
                + "\"overallRating\":4,"
                + "\"citations\":{\"c1\":{\"source\":\"peer_360\",\"reviewId\":\"r1\","
                + "\"questionId\":\"q1\",\"quote\":\"a peer quote\"}}"
                + "}";
    }

    @Test
    void generateGoldenReview_schemaInvalidThenValid_retriesAndSucceeds() {
        svc.schemaInvalidMaxRetries = 1;
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(peer360MissingAuthorJson()))   // attempt 1: invalid
                .thenReturn(geminiResult(validGoldenJson()));           // attempt 2: valid

        ManagerGuidanceService.GoldenResult result = svc.generateGoldenReview("manager draft text");

        assertNotNull(result.golden, "Second (retry) attempt produced a valid golden review");
        assertEquals(Integer.valueOf(1), result.repairRetries,
                "repairRetries must reflect the one regeneration the success required");
        verify(geminiClient, times(2)).call(any(GeminiRequest.class));
    }

    @Test
    void generateGoldenReview_retryAppendsValidationErrorsToPrompt() {
        svc.schemaInvalidMaxRetries = 1;
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(peer360MissingAuthorJson()))
                .thenReturn(geminiResult(validGoldenJson()));

        svc.generateGoldenReview("manager draft text");

        ArgumentCaptor<GeminiRequest> reqCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(2)).call(reqCaptor.capture());
        GeminiRequest secondCall = reqCaptor.getAllValues().get(1);
        assertTrue(secondCall.userPrompt().contains("authorUserId"),
                "The retry prompt must echo the prior validation errors so the model can self-correct: "
                        + secondCall.userPrompt());
    }

    @Test
    void generateGoldenReview_allAttemptsInvalid_throwsAfterBoundedRetries() {
        svc.schemaInvalidMaxRetries = 1;
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(peer360MissingAuthorJson()));   // every attempt invalid

        SchemaInvalidException ex = assertThrows(
                SchemaInvalidException.class,
                () -> svc.generateGoldenReview("manager draft text"),
                "When every attempt fails validate(), the bounded retry must still surface SCHEMA_INVALID");

        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("authorUserId")),
                "The terminal exception carries the last attempt's validation errors: " + ex.getErrors());
        assertEquals(Integer.valueOf(1), ex.repairRetries,
                "repairRetries on the terminal exception equals the number of retries spent (maxRetries)");
        verify(geminiClient, times(2)).call(any(GeminiRequest.class));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static GeminiResult geminiResult(String text) {
        return new GeminiResult(text, 100, 50, "STOP", "gemini-test", false, null);
    }

    /** A JSON body that passes ManagerGoldenReview.validate() — used as the happy-path fixture. */
    private static String validGoldenJson() {
        return "{"
                + "\"technologyRating\":4,"
                + "\"technologyComments\":\"Solid technical contributions across the cycle.\","
                + "\"consultingRating\":3,"
                + "\"consultingComments\":\"Effective stakeholder management on multiple engagements.\","
                + "\"companyValuesComments\":\"Demonstrates company values consistently.\","
                + "\"developmentOpportunities\":\"Grow executive-level communication.\","
                + "\"otherComments\":\"Solid overall performance.\","
                + "\"overallRating\":4"
                + "}";
    }
}

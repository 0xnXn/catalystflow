package com.catalystflow.ai.service;

import com.catalystflow.ai.model.Peer360Digest;
import com.catalystflow.ai.model.SchemaInvalidException;
import com.catalystflow.ai.schema.ResponseSchemas;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * RS-3 — verifies the {@code PEER_360_DIGEST} path uses Gemini's
 * {@code responseSchema} and that the {@code validate→repair→validate→throw}
 * loop has been deleted, mirroring {@link ManagerGuidanceServiceGoldenSchemaTest}.
 *
 * <p>Pure unit test, manual field wiring — same rationale as the GOLDEN
 * counterpart: bypass the test-resources {@code coaching.mock=true} default
 * and avoid Quarkus bootstrap overhead.
 */
class ManagerGuidanceServiceDigestSchemaTest {

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
        svc.digestCacheEnabled = false;
        svc.goldenCacheEnabled = false;
    }

    // ── AC-3.2: responseSchema is actually sent ──────────────────────────────

    @Test
    void generatePeer360Digest_passesResponseSchemaToGemini() {
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validDigestJson()));

        svc.generatePeer360Digest("=== Peer review r-1 ===\nQ: Q1\nA: A1\n");

        ArgumentCaptor<GeminiRequest> reqCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(1)).call(reqCaptor.capture());
        GeminiRequest captured = reqCaptor.getValue();

        assertNotNull(captured.responseSchema(),
                "RS-3: responseSchema must be populated on the GeminiRequest");
        assertEquals("peer-360-digest", captured.taskName());
        assertEquals(ResponseSchemas.schemaForPeer360Digest(), captured.responseSchema(),
                "Schema must come from ResponseSchemas.schemaForPeer360Digest() — single source of truth");
    }

    // ── AC-3.3: SchemaInvalidException thrown after ONE call, no repair retry ──

    @Test
    void digest_responseSchema_enforced() {
        // Gemini returns parseable JSON but the sentiment is outside the enum —
        // pre-RS-3 this would have hit DIGEST_REPAIR_PROMPT, post-RS-3 it
        // throws immediately because the repair loop is deleted.
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult("{"
                        + "\"themes\":[],"
                        + "\"consensus\":[],"
                        + "\"dissent\":[],"
                        + "\"overallSentiment\":\"neutral\""
                        + "}"));

        SchemaInvalidException ex = assertThrows(
                SchemaInvalidException.class,
                () -> svc.generatePeer360Digest("corpus"),
                "Schema-invalid sentiment must throw immediately; no repair retry");

        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("overallSentiment")),
                "Error must cite the bad enum value: " + ex.getErrors());
        verify(geminiClient, times(1)).call(any(GeminiRequest.class));
    }

    @Test
    void digest_unparseableJson_throwsImmediately_noRetry() {
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult("totally not json"));

        SchemaInvalidException ex = assertThrows(
                SchemaInvalidException.class,
                () -> svc.generatePeer360Digest("corpus"));

        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("not valid JSON")),
                "Unparseable response should carry the parse-failure marker: " + ex.getErrors());
        verify(geminiClient, times(1)).call(any(GeminiRequest.class));
    }

    // ── Telemetry on exception (RS-2 follow-up pattern, applied to RS-3) ──────

    @Test
    void digest_schemaInvalid_carriesTelemetryOnException() {
        // Same hard-won lesson from RS-2: discarding the wrapper on throw used
        // to lose tokenIn/tokenOut/repairRetries on the FAILED row. The
        // exception carries them through to AiReviewService.runJobInProcess
        // which persists onto AiJobs via the 7-arg updateFailed overload.
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(new GeminiResult(
                        "{\"missing\":\"required keys\"}", 180, 60, "STOP", "gemini-test", false, null));

        SchemaInvalidException ex = assertThrows(
                SchemaInvalidException.class,
                () -> svc.generatePeer360Digest("corpus"));

        assertEquals(Integer.valueOf(180), ex.tokenIn);
        assertEquals(Integer.valueOf(60), ex.tokenOut);
        assertEquals(Integer.valueOf(0), ex.repairRetries,
                "retries disabled here (hand-wired SUT leaves schemaInvalidMaxRetries=0); the bounded-retry "
                        + "path is covered by generatePeer360Digest_allAttemptsInvalid_throwsAfterBoundedRetries");
        assertEquals("gemini-test", ex.modelUsed);
    }

    // ── Success path: repairRetries pinned to 0 ──────────────────────────────

    @Test
    void generatePeer360Digest_success_repairRetriesIsZero() {
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validDigestJson()));

        ManagerGuidanceService.DigestResult result = svc.generatePeer360Digest("corpus");

        assertNotNull(result.digest);
        assertEquals("positive", result.digest.overallSentiment);
        assertEquals(Integer.valueOf(0), result.repairRetries,
                "Post-RS-3 every successful call must report repairRetries=0");
        assertEquals(200, result.tokenIn);
        assertEquals(70, result.tokenOut);
    }

    // ── CC-4b: per-task cache enablement (DIGEST) ────────────────────────────

    @Test
    void generatePeer360Digest_cacheFlagOff_sendsNoCacheRequest() {
        // Default behaviour (gemini-cache.enabled.digest=false). GeminiRequest
        // has cacheRequest=null; request body shape unchanged from RS-3.
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validDigestJson()));

        svc.generatePeer360Digest("corpus");

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
    void generatePeer360Digest_cacheFlagOn_attachesCacheRequest_correctShape() {
        // AC-10.4 — mirror of the GOLDEN test in
        // ManagerGuidanceServiceGoldenSchemaTest. DIGEST also has no
        // discipline/seniority split and no static user-role preamble:
        //  - taskType    = PEER_360_DIGEST
        //  - promptVersion = PEER_360_DIGEST_V*
        //  - discipline  = null
        //  - seniority   = null
        //  - systemPrompt = PEER_360_DIGEST_PROMPT
        //  - extraPrefix = ""     (corpus is per-job; nothing static to cache)
        //  - model       = configured gemini.model
        //  - ttl         = 600s   (§D5: 10 min — one-shot per (cycle, subject))
        svc.digestCacheEnabled = true;
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validDigestJson()));

        svc.generatePeer360Digest("corpus");

        ArgumentCaptor<GeminiRequest> reqCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(1)).call(reqCaptor.capture());
        GeminiRequest captured = reqCaptor.getValue();

        assertNotNull(captured.cacheRequest(),
                "Flag ON: cacheRequest must be populated");
        CacheRequest cr = captured.cacheRequest();
        assertEquals(TaskMetadata.TASK_PEER_360_DIGEST, cr.taskType());
        assertEquals(TaskMetadata.PROMPT_VERSION_PEER_360_DIGEST, cr.promptVersion());
        org.junit.jupiter.api.Assertions.assertNull(cr.discipline(),
                "DIGEST has no per-discipline cache split — universal prompt");
        org.junit.jupiter.api.Assertions.assertNull(cr.seniority(),
                "DIGEST has no per-seniority cache split — universal prompt");
        assertEquals("gemini-test", cr.model());
        assertEquals(600L, cr.ttlSeconds(),
                "§D5: DIGEST uses 10-min TTL (one-shot per cycle+subject)");
        assertEquals("", cr.extraPrefix(),
                "DIGEST caches the system prompt only — no static user-role prefix.");
        // Round-3 follow-up LOW: cross-equality assertion (mirror of GOLDEN).
        // Without this a typo could cache the wrong static prompt while
        // task/version/model/TTL/empty-prefix assertions all stay green.
        assertEquals(captured.systemPrompt(), cr.systemPrompt(),
                "CacheRequest.systemPrompt MUST equal the request's systemPrompt — "
                        + "otherwise the cache row holds different bytes than Gemini receives.");
    }

    @Test
    void generatePeer360Digest_cacheFlagOn_uncachedPromptEqualsUserPrompt_noByteOrderDrift() {
        // Regression guard mirroring the GOLDEN test. DIGEST has no static
        // user-role split, so uncachedPrompt MUST equal userPrompt (both are
        // the per-job peer corpus). Pins the contract so a future edit that
        // adds a static user-role preamble without updating uncachedPrompt
        // fails loudly here instead of silently sending different bytes on
        // cache miss.
        svc.digestCacheEnabled = true;
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validDigestJson()));

        svc.generatePeer360Digest("=== Peer review r-1 ===\nQ: example\nA: example answer\n");

        ArgumentCaptor<GeminiRequest> reqCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(1)).call(reqCaptor.capture());
        GeminiRequest captured = reqCaptor.getValue();

        assertEquals(captured.userPrompt(), captured.uncachedPrompt(),
                "DIGEST: uncachedPrompt must equal userPrompt (no static user-role preamble)");
    }

    // ── Bounded regeneration retry (shared with MANAGER_GOLDEN) ───────────────

    @Test
    void generatePeer360Digest_schemaInvalidThenValid_retriesAndSucceeds() {
        svc.schemaInvalidMaxRetries = 1;
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult("{\"themes\":[],\"consensus\":[],\"dissent\":[],"
                        + "\"overallSentiment\":\"neutral\"}"))   // bad enum → invalid
                .thenReturn(geminiResult(validDigestJson()));      // valid

        ManagerGuidanceService.DigestResult result = svc.generatePeer360Digest("corpus");

        assertNotNull(result.digest);
        assertEquals(Integer.valueOf(1), result.repairRetries,
                "repairRetries must reflect the one regeneration the success required");
        verify(geminiClient, times(2)).call(any(GeminiRequest.class));
    }

    @Test
    void generatePeer360Digest_allAttemptsInvalid_throwsAfterBoundedRetries() {
        svc.schemaInvalidMaxRetries = 1;
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult("{\"themes\":[],\"consensus\":[],\"dissent\":[],"
                        + "\"overallSentiment\":\"neutral\"}"));

        SchemaInvalidException ex = assertThrows(
                SchemaInvalidException.class,
                () -> svc.generatePeer360Digest("corpus"));

        assertEquals(Integer.valueOf(1), ex.repairRetries,
                "repairRetries on the terminal exception equals the retries spent (maxRetries)");
        verify(geminiClient, times(2)).call(any(GeminiRequest.class));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static GeminiResult geminiResult(String text) {
        return new GeminiResult(text, 200, 70, "STOP", "gemini-test", false, null);
    }

    /** Passes Peer360Digest.validate() — happy path fixture. */
    private static String validDigestJson() {
        return "{"
                + "\"themes\":[{\"theme\":\"Delivery leadership\","
                + "\"summary\":\"Peers consistently call out strong delivery ownership.\"}],"
                + "\"consensus\":[{\"point\":\"Takes ownership of stakeholder relationships.\","
                + "\"reviewIds\":[\"r-1\",\"r-2\"]}],"
                + "\"dissent\":[],"
                + "\"overallSentiment\":\"positive\""
                + "}";
    }
}

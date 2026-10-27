package com.catalystflow.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * Unit tests for {@link GeminiClient} — the extracted Gemini HTTP layer (RS-1).
 *
 * <p>Pure JUnit; the SUT is constructed directly and its package-private
 * fields (config + {@code httpClient}) are wired by hand. {@code HttpClient}
 * is not a CDI bean so {@code @InjectMock} does not apply — the test mocks
 * the abstract {@link HttpClient} class with Mockito and assigns it to the
 * package-private {@link GeminiClient#httpClient} field, the same approach
 * that {@code AiReviewServiceRateLimitTest} suggested in its closing comment.
 *
 * <p>Covers the five scenarios spelled out in story RS-1 AC-1.6 plus the
 * optional 429-exhausted case from AC-1.6.7.
 */
class GeminiClientTest {

    private GeminiClient client;
    private HttpClient http;
    private GeminiCacheService cacheService;

    private java.io.ByteArrayOutputStream emfBuffer;

    @BeforeEach
    void setup() {
        http = mock(HttpClient.class);
        // CC-3: GeminiClient now injects GeminiCacheService for cache resolution.
        // Mocked here so the existing call_* tests (which pass cacheRequest=null)
        // never invoke it; the new CC-3 tests stub specific behaviour per case.
        cacheService = mock(GeminiCacheService.class);
        client = new GeminiClient();
        client.geminiApiKey = "test-key";
        // Model is no longer a client-level field — it travels per-request on
        // GeminiRequest.model. Every of() call in this test passes "gemini-test"
        // as the explicit model, so the URL build always sees it.
        client.logLlmPreviews = false;
        client.mapper = new ObjectMapper();
        client.httpClient = http;
        client.cacheService = cacheService;
        client.stage = "test";
        // CC-5 follow-up: capture EMF emissions to a buffer so tests can
        // assert the rendered bytes are parseable JSON. Production sink is
        // System.out (Lambda's stdout → CloudWatch Logs → EMF parser).
        emfBuffer = new java.io.ByteArrayOutputStream();
        client.emfSink = new java.io.PrintStream(emfBuffer, true,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── AC-1.6.3: happy path ─────────────────────────────────────────────────

    @Test
    void call_200_returnsTextAndTokens() throws Exception {
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, candidateBody("OK", 10, 20, "STOP")));

        GeminiResult r = client.call(
                GeminiRequest.of("happy-test", "sys", "user", 1024, 256, "gemini-test"));

        assertEquals("OK", r.text());
        assertEquals(10, r.tokenIn());
        assertEquals(20, r.tokenOut());
        assertEquals("STOP", r.finishReason());
        assertEquals("gemini-test", r.modelUsed());
        assertFalse(r.cacheHit(), "happy-path call has no cacheRequest → cacheHit false");
        verify(http, times(1)).send(any(), any());
    }

    // ── AC-1.6.4: 5xx retries once ───────────────────────────────────────────

    @Test
    void call_5xx_retriesOnce_thenSucceeds() throws Exception {
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(503, "{}"))
                .thenReturn(fakeJsonResponse(200, candidateBody("recovered", 5, 7, "STOP")));

        GeminiResult r = client.call(
                GeminiRequest.of("retry-test", "sys", "user", 1024, 256, "gemini-test"));

        assertEquals("recovered", r.text());
        verify(http, times(2)).send(any(), any());
    }

    // ── AC-1.6.5: MAX_TOKENS → GeminiTelemetryException with token counts ────

    @Test
    void call_maxTokens_throwsGeminiTelemetryException() throws Exception {
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200,
                        candidateBody("partial...", 100, 65_536, "MAX_TOKENS")));

        GeminiTelemetryException ex = assertThrows(
                GeminiTelemetryException.class,
                () -> client.call(GeminiRequest.of("max-test", "sys", "user", 1024, 256, "gemini-test")));

        assertNotNull(ex.tokenIn);
        assertNotNull(ex.tokenOut);
        assertEquals(100, ex.tokenIn);
        assertEquals(65_536, ex.tokenOut);
        assertEquals("gemini-test", ex.modelUsed);
        assertTrue(ex.getMessage().contains("truncated"),
                "Message should explain truncation: " + ex.getMessage());
    }

    // ── AC-1.6.6: HttpTimeoutException → no retry, immediate GATEWAY_TIMEOUT ─

    @Test
    void call_timeout_doesNotRetry() throws Exception {
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenThrow(new HttpTimeoutException("deadline"));

        WebApplicationException ex = assertThrows(
                WebApplicationException.class,
                () -> client.call(GeminiRequest.of("timeout-test", "sys", "user", 1024, 256, "gemini-test")));

        assertEquals(504, ex.getResponse().getStatus());
        verify(http, times(1)).send(any(), any());
    }

    // ── AC-1.6.7 (optional but recommended): 429 retries exhausted ───────────

    @Test
    void call_429Exhausted_throwsGeminiRateLimitedException() throws Exception {
        // 3 consecutive 429s = original attempt + MAX_429_RETRIES (2). Retry-After: 1.
        HttpResponse<String> rl = fake429Response(1);
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(rl)
                .thenReturn(rl)
                .thenReturn(rl);

        GeminiRateLimitedException ex = assertThrows(
                GeminiRateLimitedException.class,
                () -> client.call(GeminiRequest.of("429-test", "sys", "user", 1024, 256, "gemini-test")),
                "Three consecutive 429s must exhaust the in-process budget");

        assertEquals(1L, ex.retryAfterSeconds);
        assertTrue(ex.getMessage().startsWith("RATE_LIMITED"),
                "Message must use the RATE_LIMITED prefix so classifyError picks it up: " + ex.getMessage());
        verify(http, times(1 + GeminiClient.MAX_429_RETRIES)).send(any(), any());
    }

    // ── Added per code-review (parity paths previously unpinned) ─────────────

    @Test
    void call_transient5xx_afterOneRetry_throwsTransientUpstream() throws Exception {
        // Two consecutive 503s — in-process retry budget exhausted. A retryable
        // transient upstream (503 "model overloaded") now defers via SQS through
        // GeminiTransientUpstreamException instead of failing terminal BAD_GATEWAY.
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(503, "{}"))
                .thenReturn(fakeJsonResponse(503, "{}"));

        GeminiTransientUpstreamException ex = assertThrows(
                GeminiTransientUpstreamException.class,
                () -> client.call(GeminiRequest.of("term-5xx", "sys", "user", 1024, 256, "gemini-test")));

        assertEquals(503, ex.upstreamCode,
                "real upstream code must be carried for the worker's UPSTREAM_5XX FAILED tag");
        assertTrue(ex.getMessage().contains("upstream 503"), ex.getMessage());
        verify(http, times(2)).send(any(), any());
    }

    @Test
    void call_nonTransient5xx_afterOneRetry_throwsBadGateway() throws Exception {
        // 501 is NOT in the transient set {500,502,503} — after the one retry it
        // stays a terminal BAD_GATEWAY so classifyError tags it UPSTREAM_5XX (501)
        // (no infinite SQS deferral on a permanent server error).
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(501, "{}"));

        WebApplicationException ex = assertThrows(
                WebApplicationException.class,
                () -> client.call(GeminiRequest.of("nt-5xx", "sys", "user", 1024, 256, "gemini-test")));

        assertEquals(502, ex.getResponse().getStatus(), "BAD_GATEWAY wrapper");
        assertTrue(ex.getMessage().contains("upstream 501"), ex.getMessage());
        verify(http, times(2)).send(any(), any());
    }

    @Test
    void call_non429_4xx_doesNotRetry() throws Exception {
        // 400 from Gemini = bad request; no retry, immediate BAD_GATEWAY wrapper.
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(400, "{\"error\":\"bad request\"}"));

        WebApplicationException ex = assertThrows(
                WebApplicationException.class,
                () -> client.call(GeminiRequest.of("non-429-4xx", "sys", "user", 1024, 256, "gemini-test")));

        assertEquals(502, ex.getResponse().getStatus());
        assertTrue(ex.getMessage().contains("upstream 400"),
                "Real 400 must be in the message so classifyError reports UPSTREAM_4XX (400): " + ex.getMessage());
        verify(http, times(1)).send(any(), any());
    }

    @Test
    void call_ioFailureTwice_throwsTransientUpstream() throws Exception {
        // First IOException retried once; second IOException is transient (network),
        // so it now defers via SQS through GeminiTransientUpstreamException
        // (upstreamCode 0 = "IO, not an HTTP status") instead of terminal 503.
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenThrow(new IOException("connection reset 1"))
                .thenThrow(new IOException("connection reset 2"));

        GeminiTransientUpstreamException ex = assertThrows(
                GeminiTransientUpstreamException.class,
                () -> client.call(GeminiRequest.of("io-fail", "sys", "user", 1024, 256, "gemini-test")));

        assertEquals(0, ex.upstreamCode, "IO cause carries upstreamCode 0 → classifyError tags UNAVAILABLE");
        verify(http, times(2)).send(any(), any());
    }

    @Test
    void call_noCandidates_throwsGeminiTelemetryException() throws Exception {
        // 200 OK but Gemini returned no candidates — telemetry must persist
        // tokenIn/tokenOut on the FAILED row, message must be the standard tag.
        String body = "{"
                + "\"candidates\":[],"
                + "\"usageMetadata\":{\"promptTokenCount\":50,\"candidatesTokenCount\":0}"
                + "}";
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, body));

        GeminiTelemetryException ex = assertThrows(
                GeminiTelemetryException.class,
                () -> client.call(GeminiRequest.of("no-cand", "sys", "user", 1024, 256, "gemini-test")));

        assertEquals(50, ex.tokenIn);
        assertEquals(0, ex.tokenOut);
        assertEquals("gemini-test", ex.modelUsed);
        assertTrue(ex.getMessage().contains("no candidates"),
                "Message must use the 'no candidates returned' wording for classifyError MALFORMED bucket: "
                        + ex.getMessage());
    }

    @Test
    void call_thoughtParts_skippedInText() throws Exception {
        // Gemini 2.5 thinking models emit reasoning in earlier parts marked
        // thought:true. parseResponse must skip those and only concatenate
        // non-thought parts. This protects the "responseChars" log + the
        // contract that text() is the visible answer, not the reasoning.
        String body = "{"
                + "\"candidates\":[{"
                + "  \"finishReason\":\"STOP\","
                + "  \"content\":{\"parts\":["
                + "    {\"thought\":true,\"text\":\"REASONING_HIDDEN\"},"
                + "    {\"text\":\"visible1 \"},"
                + "    {\"thought\":true,\"text\":\"MORE_REASONING\"},"
                + "    {\"text\":\"visible2\"}"
                + "  ]}"
                + "}],"
                + "\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":2}"
                + "}";
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, body));

        GeminiResult r = client.call(
                GeminiRequest.of("multipart-thought", "sys", "user", 1024, 256, "gemini-test"));

        assertEquals("visible1 visible2", r.text(),
                "Thought parts must be dropped; only visible parts concatenated in order");
        assertFalse(r.text().contains("REASONING_HIDDEN"));
        assertFalse(r.text().contains("MORE_REASONING"));
    }

    // callCachedContents_* tests moved to GeminiCacheTransportTest as part of
    // the CC-2 follow-up (callCachedContents lives on GeminiCacheTransport now).

    // ── CC-3: cache wiring on call() ─────────────────────────────────────────

    @Test
    void call_noCacheRequest_doesNotConsultCacheService_bodyShapeUnchanged() throws Exception {
        // AC-9.3 regression guard: when cacheRequest is null (today's default),
        // GeminiClient must NOT consult cacheService and must build the body
        // with system_instruction (no cachedContent field) — exactly today's
        // behaviour. This is the cache failures are invisible promise.
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, candidateBody("OK", 10, 20, "STOP")));

        GeminiResult r = client.call(GeminiRequest.of("plain", "sys", "user", 1024, 256, "gemini-test"));

        assertFalse(r.cacheHit(), "no cacheRequest → cacheHit must be false");
        org.mockito.Mockito.verifyNoInteractions(cacheService);
    }

    @Test
    void call_cacheableHit_sendsCachedContentAndOmitsSystemInstruction() throws Exception {
        // AC-9.1 + AC-9.2: cacheRequest non-null → resolveOrCreate → cacheHit
        // → request body uses cachedContent and OMITS system_instruction.
        // The user message is userPrompt (dynamic-only) — uncachedPrompt is
        // NEVER consulted on hit, only on miss.
        org.mockito.Mockito.when(cacheService.resolveOrCreate(org.mockito.ArgumentMatchers.any(CacheRequest.class)))
                .thenReturn("cachedContents/abc");
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, candidateBody("cached", 50, 30, "STOP")));

        GeminiResult r = client.call(GeminiRequest.of("cache-hit", "sys-prompt", "user-prompt", 1024, 256, "gemini-test")
                .withCacheRequest(sampleCacheRequest(), "MUST_NOT_APPEAR_ON_HIT"));

        assertTrue(r.cacheHit(), "AC-9.4: cacheHit must reflect true on hit");
        assertEquals("cached", r.text());
        // Inspect the body sent to Gemini.
        String sent = capturedRequestBody();
        assertTrue(sent.contains("\"cachedContent\":\"cachedContents/abc\""),
                "Body must include cachedContent: " + sent);
        assertFalse(sent.contains("system_instruction"),
                "Body must OMIT system_instruction when cached: " + sent);
        assertFalse(sent.contains("sys-prompt"),
                "system_instruction text must not appear in the cached body: " + sent);
        assertTrue(sent.contains("user-prompt"),
                "userPrompt (dynamic-only) must be sent as the user message on hit: " + sent);
        assertFalse(sent.contains("MUST_NOT_APPEAR_ON_HIT"),
                "MEDIUM #3 follow-up: uncachedPrompt must NEVER appear in the body on cache hit "
                        + "— only userPrompt does. If this fails the client is mixing the two paths: " + sent);
    }

    @Test
    void call_cacheableMissDegradedNull_sendsUncachedPromptVerbatim() throws Exception {
        // AC-9.3: when resolveOrCreate returns null (cache layer degraded —
        // too-small content, DDB outage, Gemini cachedContents 4xx, ...),
        // GeminiClient sends the uncached body using uncachedPrompt VERBATIM
        // — no reconstruction inside the client.
        //
        // MEDIUM #3 follow-up: previously the client appended
        // cacheRequest.extraPrefix() after userPrompt to rebuild the full
        // prompt. That baked QUESTION_SUGGEST's dynamic-then-static byte
        // order into a shared client and would silently corrupt any future
        // task whose cache-OFF order is different. Now the caller passes the
        // FULL pre-rendered prompt as uncachedPrompt — caller owns byte
        // order; client just sends bytes.
        //
        // Contract pinned here: the body must contain uncachedPrompt verbatim,
        // and MUST NOT contain userPrompt (the dynamic-only half) on miss.
        org.mockito.Mockito.when(cacheService.resolveOrCreate(org.mockito.ArgumentMatchers.any(CacheRequest.class)))
                .thenReturn(null);
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, candidateBody("uncached", 200, 50, "STOP")));

        String dynamicOnly = "DYNAMIC_ONLY_USER_PROMPT";
        String fullUncached = "FULL_UNCACHED_USER_MESSAGE_WITH_PREFIX_AND_DYNAMIC";

        GeminiResult r = client.call(GeminiRequest.of("cache-miss", "sys-prompt", dynamicOnly, 1024, 256, "gemini-test")
                .withCacheRequest(sampleCacheRequest(), fullUncached));

        assertFalse(r.cacheHit(), "cacheHit must be false when resolveOrCreate returned null");
        assertEquals("uncached", r.text());
        String sent = capturedRequestBody();
        assertTrue(sent.contains("system_instruction"),
                "Uncached fallback body must include system_instruction: " + sent);
        assertFalse(sent.contains("cachedContent"),
                "Uncached fallback must NOT include cachedContent: " + sent);
        assertTrue(sent.contains("sys-prompt"),
                "System prompt text must appear when uncached: " + sent);
        // The uncachedPrompt must be sent verbatim.
        assertTrue(sent.contains(fullUncached),
                "Cache-miss path MUST send uncachedPrompt verbatim: " + sent);
        // The dynamic-only userPrompt must NOT appear — proves the client
        // chose uncachedPrompt over userPrompt on miss.
        assertFalse(sent.contains(dynamicOnly),
                "Cache-miss path MUST NOT send the dynamic-only userPrompt — "
                        + "uncachedPrompt is the caller's pre-rendered full message and "
                        + "should fully replace it on miss: " + sent);
    }

    @Test
    void withCacheRequest_nullUncachedPrompt_throws_failLoudAtBuild() {
        // MEDIUM #3 follow-up: a future task that opts into the cache must
        // pass uncachedPrompt. The two-arg withCacheRequest validates this at
        // build time so the trap (silently sending dynamic-only on cache miss
        // — the CC-4a CRITICAL #1 bug) becomes a fail-loud builder error
        // instead of a quality regression discovered in production.
        GeminiRequest base = GeminiRequest.of("forgot-uncached", "sys", "user", 1024, 256, "gemini-test");
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> base.withCacheRequest(sampleCacheRequest(), null),
                "withCacheRequest(req, null) must fail loudly at the builder, "
                        + "not defer the bug to cache-miss runtime");
        assertTrue(ex.getMessage().contains("uncachedPrompt"),
                "Error message must name the parameter so the fix is obvious: " + ex.getMessage());
    }

    @Test
    void canonicalConstructor_cacheRequestWithoutUncachedPrompt_throws() {
        // CC-4a follow-up round-3 MEDIUM: the public record constructor
        // bypassed the builder's validation pre-fix. A caller doing
        // `new GeminiRequest(..., cacheReq, null)` compiled silently; on
        // cache miss GeminiClient would NPE inside Map.of("text", null),
        // violating AC-9.3 (cache failures must be invisible). The compact
        // canonical constructor now enforces the invariant at every
        // construction site — no bypass is possible without reflection.
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new GeminiRequest(
                        "bypass-attempt", "sys", "user", 1024, 256,
                        null, sampleCacheRequest(), null, "gemini-test"),
                "Direct-ctor bypass (cacheRequest != null + uncachedPrompt == null) "
                        + "must throw at construction, not late at HTTP send");
        assertTrue(ex.getMessage().contains("uncachedPrompt"),
                "Error message must name the missing parameter: " + ex.getMessage());
    }

    @Test
    void canonicalConstructor_noCacheRequest_uncachedPromptMayBeNull() {
        // Negative: the invariant is conditional. When cacheRequest is null
        // (plain uncached call — the today-default path), uncachedPrompt
        // being null is harmless and must NOT throw. Otherwise every
        // GeminiRequest.of(...) call would break.
        GeminiRequest req = new GeminiRequest(
                "plain", "sys", "user", 1024, 256,
                null, null, null, "gemini-test");
        assertNotNull(req, "Plain uncached call (no cacheRequest) must allow null uncachedPrompt");
    }

    @Test
    void call_cacheServiceThrows_degradesToUncached_doesNotPropagate() throws Exception {
        // Defence-in-depth: GeminiCacheService.resolveOrCreate is contracted to
        // never throw, but if a future change leaks an exception the user-facing
        // call must still succeed without cache (not 500 the whole job).
        org.mockito.Mockito.when(cacheService.resolveOrCreate(org.mockito.ArgumentMatchers.any(CacheRequest.class)))
                .thenThrow(new RuntimeException("cache layer broke"));
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, candidateBody("survived", 100, 40, "STOP")));

        GeminiResult r = client.call(GeminiRequest.of("cache-throw", "sys-prompt", "user-prompt", 1024, 256, "gemini-test")
                .withCacheRequest(sampleCacheRequest(), "uncached-fallback-text"));

        assertFalse(r.cacheHit());
        assertEquals("survived", r.text(),
                "Cache layer exception must not break the user-facing call");
        String sent = capturedRequestBody();
        assertTrue(sent.contains("system_instruction"),
                "Uncached fallback body must include system_instruction after cache throw: " + sent);
        assertTrue(sent.contains("uncached-fallback-text"),
                "Cache-throw path uses the same fallback as cache-miss → must send uncachedPrompt: " + sent);
    }

    // ── CC-3 follow-up: code-review regression guards ────────────────────────

    @Test
    void call_cacheServiceReturnsBlank_fallsBackUncachedWithCacheHitFalse() throws Exception {
        // LOW from code review: pre-fix, cacheHit was (resolvedCacheName != null)
        // while the body only used the cache when the name was non-blank. So
        // a blank or whitespace cacheName produced cacheHit=true on an
        // uncached body — AC-9.4 violation. Post-fix, both checks align:
        // cacheHit = non-null AND non-blank, and the body uses the same
        // discriminator.
        org.mockito.Mockito.when(cacheService.resolveOrCreate(org.mockito.ArgumentMatchers.any(CacheRequest.class)))
                .thenReturn("   ");  // whitespace — not a usable cache name
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, candidateBody("uncached-fallback", 80, 30, "STOP")));

        GeminiResult r = client.call(GeminiRequest.of("blank-cache", "sys-prompt", "user-prompt", 1024, 256, "gemini-test")
                .withCacheRequest(sampleCacheRequest(), "uncached-fallback-for-blank"));

        assertFalse(r.cacheHit(),
                "Blank cacheName must NOT produce cacheHit=true (single source of truth contract)");
        String sent = capturedRequestBody();
        assertTrue(sent.contains("system_instruction"),
                "Blank cacheName must fall through to system_instruction body: " + sent);
        assertFalse(sent.contains("cachedContent"),
                "Body must NOT include cachedContent when name is blank: " + sent);
    }

    @Test
    void safeCacheKeyForLog_nullInput_returnsUnavailableNotThrow() {
        // Unit-level proof of the safeCacheKeyForLog helper. Call-path
        // proof is below in call_composeCacheKeyThrows_userFacingCallStillSucceeds.
        String safeKey = client.safeCacheKeyForLog(null);
        assertEquals("[unavailable]", safeKey,
                "safeCacheKeyForLog must return [unavailable] not propagate any exception");
    }

    @Test
    void safeCacheKeyForLog_composerThrows_returnsUnavailable() {
        // Direct proof that the catch path in safeCacheKeyForLog actually
        // fires when the composer function throws. Pairs with the call-path
        // test below: this one pins the helper, the next one pins the
        // protection at the call() boundary.
        client.cacheKeyComposer = req -> { throw new RuntimeException("simulated"); };
        String safeKey = client.safeCacheKeyForLog(sampleCacheRequest());
        assertEquals("[unavailable]", safeKey);
    }

    @Test
    void call_composeCacheKeyThrows_userFacingCallStillSucceeds() throws Exception {
        // Stronger regression for the AC-9.3 cache-failures-invisible
        // contract. If a future edit accidentally bypasses safeCacheKeyForLog
        // and calls GeminiCacheService.composeCacheKey directly, the helper
        // unit tests above keep passing but the call() path would leak the
        // throw. This test forces the composer to throw via the swappable
        // cacheKeyComposer field, then proves client.call() still returns
        // successfully with the uncached body — the only way that's true is
        // if the log-path composition is protected end-to-end.
        //
        // (Static mocking of GeminiCacheService.composeCacheKey would be the
        // textbook approach, but Quarkus uses the subclass mock-maker which
        // doesn't support static mocks. The cacheKeyComposer field is the
        // test seam that solves the same problem without fighting the
        // mock-maker.)
        org.mockito.Mockito.when(cacheService.resolveOrCreate(org.mockito.ArgumentMatchers.any(CacheRequest.class)))
                .thenReturn(null); // cache miss → uncached fallback fires
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, candidateBody("survived", 100, 40, "STOP")));
        client.cacheKeyComposer = req -> {
            throw new RuntimeException("simulated composeCacheKey failure");
        };

        GeminiResult r = client.call(GeminiRequest.of("compose-throws", "sys", "user", 1024, 256, "gemini-test")
                .withCacheRequest(sampleCacheRequest(), "uncached-fallback-for-compose-throw"));

        assertFalse(r.cacheHit(),
                "Cache resolution returned null → cacheHit must be false");
        assertEquals("survived", r.text(),
                "User-facing call must survive composeCacheKey throw via safeCacheKeyForLog");
        // Body must still be the uncached shape — proves the throw didn't
        // short-circuit between cache resolution and HTTP send.
        String sent = capturedRequestBody();
        assertTrue(sent.contains("system_instruction"),
                "Uncached fallback body must include system_instruction after compose throw: " + sent);
    }

    // ── RJS-1: response_json_schema wire-field regression guard ──────────────

    @Test
    void call_withResponseSchema_writesResponseJsonSchemaField_notLegacyResponseSchema() throws Exception {
        // RJS-1 review follow-up: the change from `responseSchema` to
        // `response_json_schema` is coupled to ResponseSchemas re-introducing
        // `additionalProperties`. If someone reverts the GeminiClient field
        // name to the legacy `responseSchema` without also stripping
        // `additionalProperties`, Gemini silently rejects the call (or worse,
        // accepts it and ignores the schema). This test pins the wire field
        // name so a partial revert breaks the build.
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, candidateBody("OK", 10, 20, "STOP")));

        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("foo", Map.of("type", "string")),
                "required", List.of("foo"));
        client.call(GeminiRequest.of("rjs1-test", "sys", "user", 1024, 256, "gemini-test")
                .withResponseSchema(schema));

        String body = capturedRequestBody();
        assertTrue(body.contains("\"response_json_schema\""),
                "RJS-1: body must use snake_case `response_json_schema` field. Body: " + body);
        assertFalse(body.contains("\"responseSchema\""),
                "RJS-1: legacy `responseSchema` field must NOT appear — mutually exclusive "
                + "with `response_json_schema`. If both appear, Gemini's behaviour is "
                + "implementation-defined. Body: " + body);
    }

    // Helper for CC-3 body-shape assertions.
    private String capturedRequestBody() throws Exception {
        org.mockito.ArgumentCaptor<HttpRequest> cap =
                org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(cap.capture(), any());
        return requestBodyOf(cap.getValue());
    }

    /** Extract a sent HttpRequest's JSON body — BodyPublisher is opaque, so subscribe and accumulate. */
    private static String requestBodyOf(HttpRequest sent) {
        java.util.concurrent.atomic.AtomicReference<String> result = new java.util.concurrent.atomic.AtomicReference<>("");
        sent.bodyPublisher().ifPresent(pub -> {
            java.util.concurrent.CompletableFuture<String> done = new java.util.concurrent.CompletableFuture<>();
            pub.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                final StringBuilder sb = new StringBuilder();
                @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                @Override public void onNext(java.nio.ByteBuffer item) {
                    byte[] bytes = new byte[item.remaining()];
                    item.get(bytes);
                    sb.append(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                }
                @Override public void onError(Throwable t) { done.completeExceptionally(t); }
                @Override public void onComplete() { done.complete(sb.toString()); }
            });
            try { result.set(done.get(2, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (Exception ignored) {}
        });
        return result.get();
    }

    private static CacheRequest sampleCacheRequest() {
        return new CacheRequest("cache-hit", "ANALYZE_V2",
                "DevOps", "Engineer", "sys-prompt", "extra",
                "gemini-test", 3600);
    }

    // ── INSIGHTS streaming (callStreaming) ───────────────────────────────────

    @Test
    void callStreaming_invokesChunkHandler_withCumulativeText() throws Exception {
        // 3 SSE chunks → text accumulates → handler sees growing snapshots.
        // Final chunk carries usageMetadata + finishReason.
        String sse =
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Aarti \"}]}}]}\n\n" +
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"demonstrated \"}]}}]}\n\n" +
                "data: {\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"strong execution.\"}]}}]," +
                "\"usageMetadata\":{\"promptTokenCount\":2807,\"candidatesTokenCount\":2190}}\n\n";

        when(http.send(any(HttpRequest.class), streamingBodyHandler()))
                .thenReturn(fakeStreamingResponse(200, sse));

        java.util.List<String> snapshots = new java.util.ArrayList<>();
        GeminiResult r = client.callStreaming(
                GeminiRequest.of("insights", "sys", "user", 65_536, 12_288, "gemini-test"),
                snapshots::add);

        // Final flush guarantees at least one handler invocation with the full text.
        assertFalse(snapshots.isEmpty(), "handler must be invoked at least once (final flush)");
        String last = snapshots.get(snapshots.size() - 1);
        assertEquals("Aarti demonstrated strong execution.", last,
                "final snapshot must be cumulative concatenation of all text parts");
        // Each snapshot must be a prefix of the next (cumulative, not delta).
        for (int i = 1; i < snapshots.size(); i++) {
            assertTrue(last.startsWith(snapshots.get(i - 1)),
                    "snapshot " + (i - 1) + " must be a prefix of later snapshots — got "
                            + snapshots.get(i - 1) + " vs final " + last);
        }
        // usageMetadata threaded into the final result.
        assertEquals("Aarti demonstrated strong execution.", r.text());
        assertEquals(Integer.valueOf(2807), r.tokenIn());
        assertEquals(Integer.valueOf(2190), r.tokenOut());
        assertEquals("STOP", r.finishReason());
        assertEquals("gemini-test", r.modelUsed());
    }

    @Test
    void callStreaming_skipsThoughtParts() throws Exception {
        // Gemini 2.5+ may emit `thought: true` parts for internal reasoning.
        // Those MUST NOT appear in partialText (or in the final concatenated buffer).
        String sse =
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"hidden reasoning\"}]}}]}\n\n" +
                "data: {\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"visible output\"}]}}]," +
                "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5}}\n\n";

        when(http.send(any(HttpRequest.class), streamingBodyHandler()))
                .thenReturn(fakeStreamingResponse(200, sse));

        java.util.List<String> snapshots = new java.util.ArrayList<>();
        GeminiResult r = client.callStreaming(
                GeminiRequest.of("insights", "sys", "user", 100, 50, "gemini-test"),
                snapshots::add);

        assertEquals("visible output", r.text(),
                "thought parts must be excluded from the response text");
        // No snapshot should contain the hidden reasoning text.
        for (String s : snapshots) {
            assertFalse(s.contains("hidden reasoning"),
                    "snapshot leaked thought text: " + s);
        }
    }

    @Test
    void callStreaming_nonTransient5xx_throwsBadGateway() throws Exception {
        // 501 is not transient → terminal BAD_GATEWAY (after the one 5xx retry).
        when(http.send(any(HttpRequest.class), streamingBodyHandler()))
                .thenReturn(fakeStreamingResponse(501, "{\"error\":\"not implemented\"}"));

        java.util.List<String> snapshots = new java.util.ArrayList<>();
        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> client.callStreaming(
                        GeminiRequest.of("insights", "sys", "user", 100, 50, "gemini-test"),
                        snapshots::add));
        assertEquals(502, ex.getResponse().getStatus(),
                "non-transient 5xx streaming response must surface as BAD_GATEWAY");
        assertTrue(snapshots.isEmpty(),
                "no chunkHandler invocations when stream never opened successfully");
    }

    @Test
    void callStreaming_transient5xx_retriesOnce_thenSucceeds() throws Exception {
        // The headline fix for the 2026-05-29 incident: a single transient 503 on
        // the streaming path no longer kills the job — it is retried in-process and
        // the second attempt streams normally.
        String sse =
                "data: {\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"recovered output\"}]}}]," +
                "\"usageMetadata\":{\"promptTokenCount\":12,\"candidatesTokenCount\":6}}\n\n";
        when(http.send(any(HttpRequest.class), streamingBodyHandler()))
                .thenReturn(fakeStreamingResponse(503, "{\"error\":\"model overloaded\"}"))
                .thenReturn(fakeStreamingResponse(200, sse));

        GeminiResult r = client.callStreaming(
                GeminiRequest.of("insights", "sys", "user", 100, 50, "gemini-test"),
                s -> {});

        assertEquals("recovered output", r.text(), "second attempt must stream successfully");
        verify(http, times(2)).send(any(), streamingBodyHandler());
    }

    @Test
    void callStreaming_transient5xx_twice_throwsTransientUpstream() throws Exception {
        // Two consecutive 503s on the streaming path — in-process retry exhausted,
        // defer via SQS (GeminiTransientUpstreamException) rather than fail terminal.
        when(http.send(any(HttpRequest.class), streamingBodyHandler()))
                .thenReturn(fakeStreamingResponse(503, "{\"error\":\"model overloaded\"}"))
                .thenReturn(fakeStreamingResponse(503, "{\"error\":\"model overloaded\"}"));

        GeminiTransientUpstreamException ex = assertThrows(GeminiTransientUpstreamException.class,
                () -> client.callStreaming(
                        GeminiRequest.of("insights", "sys", "user", 100, 50, "gemini-test"),
                        s -> {}));
        assertEquals(503, ex.upstreamCode);
        verify(http, times(2)).send(any(), streamingBodyHandler());
    }

    @Test
    void callStreaming_429_throwsGeminiRateLimitedException() throws Exception {
        when(http.send(any(HttpRequest.class), streamingBodyHandler()))
                .thenReturn(fakeStreamingResponse(429, "{\"error\":\"quota\"}"));

        assertThrows(GeminiRateLimitedException.class,
                () -> client.callStreaming(
                        GeminiRequest.of("insights", "sys", "user", 100, 50, "gemini-test"),
                        s -> {}),
                "streaming 429 must surface as GeminiRateLimitedException so the worker can defer via SQS");
    }

    @Test
    void callStreaming_finishReasonMAX_TOKENS_throwsGeminiTelemetry() throws Exception {
        // Even though we got 200 OK + valid SSE, finishReason=MAX_TOKENS means
        // the response was truncated and the structured JSON parse would fail
        // upstream. Surface as GeminiTelemetryException so the worker persists
        // token spend on the FAILED row.
        String sse =
                "data: {\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[{\"text\":\"partial\"}]}}]," +
                "\"usageMetadata\":{\"promptTokenCount\":100,\"candidatesTokenCount\":65536}}\n\n";

        when(http.send(any(HttpRequest.class), streamingBodyHandler()))
                .thenReturn(fakeStreamingResponse(200, sse));

        assertThrows(GeminiTelemetryException.class,
                () -> client.callStreaming(
                        GeminiRequest.of("insights", "sys", "user", 65_536, 12_288, "gemini-test"),
                        s -> {}));
    }

    @Test
    void callStreaming_chunkHandlerException_swallowedAndStreamContinues() throws Exception {
        // Defensive contract: a throwing chunkHandler (e.g. DDB transient outage
        // surfaced via aiJobRepository.updatePartialText) must NOT abort the
        // underlying Gemini call. The terminal Gemini result still arrives.
        String sse =
                "data: {\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"streamed text\"}]}}]," +
                "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5}}\n\n";

        when(http.send(any(HttpRequest.class), streamingBodyHandler()))
                .thenReturn(fakeStreamingResponse(200, sse));

        GeminiResult r = client.callStreaming(
                GeminiRequest.of("insights", "sys", "user", 100, 50, "gemini-test"),
                snapshot -> { throw new RuntimeException("simulated DDB outage"); });

        assertEquals("streamed text", r.text(),
                "callStreaming must return successfully even when chunkHandler throws");
    }

    // ── mid-stream interruption: do NOT default a cut stream to STOP ──────────

    @Test
    void callStreaming_interruptedByMidStream503_throwsTransientNotDefaultStop() throws Exception {
        // 2026-06-02 root cause: gemini-3.5-flash's streaming endpoint, when
        // overloaded, emits partial content then a MID-STREAM
        // {"error":{"code":503,"status":"UNAVAILABLE"}} trailer and closes the
        // stream. That trailer is NOT a `data:` frame, so the SSE loop used to
        // silently drop it; with no finishReason received it defaulted to STOP and
        // returned the truncated buffer — which the INSIGHTS parser then rejected
        // as terminal MALFORMED. A stream that ends without a terminal finishReason
        // is an interrupted/transient failure: it must defer via SQS
        // (GeminiTransientUpstreamException), which also lets the SPEC-503
        // redelivery flip to the fallback model. The real 503 code must be carried.
        String sse =
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{ \\\"summary\\\": \\\"- partial\"}]}}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":2881,\"candidatesTokenCount\":7}}\n\n" +
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" content that never closes\"}]}}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":2881,\"candidatesTokenCount\":31}}\n\n" +
                "{\"error\":{\"code\":503,\"message\":\"This model is currently experiencing high demand.\","
                        + "\"status\":\"UNAVAILABLE\"}}\n";

        when(http.send(any(HttpRequest.class), streamingBodyHandler()))
                .thenReturn(fakeStreamingResponse(200, sse));

        java.util.List<String> snapshots = new java.util.ArrayList<>();
        GeminiTransientUpstreamException ex = assertThrows(GeminiTransientUpstreamException.class,
                () -> client.callStreaming(
                        GeminiRequest.of("insights", "sys", "user", 65_536, 2_048, "gemini-test"),
                        snapshots::add),
                "a stream cut mid-flight by a 503 must defer via SQS, not return a STOP result with truncated JSON");
        assertEquals(503, ex.upstreamCode,
                "the mid-stream 503 code must be carried so the worker tags UPSTREAM_5XX and the redelivery flips to fallback");
    }

    @Test
    void callStreaming_endsWithoutFinishReason_throwsTransientUpstream() throws Exception {
        // Even with no {"error":...} trailer (e.g. the connection simply drops),
        // a stream that ends with no terminal finishReason is incomplete and must
        // be a retryable transient — never defaulted to STOP. upstreamCode 0 marks
        // "interrupted, no HTTP status" (same convention as a post-retry IO drop).
        String sse =
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"partial only\"}]}}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":3}}\n\n";

        when(http.send(any(HttpRequest.class), streamingBodyHandler()))
                .thenReturn(fakeStreamingResponse(200, sse));

        GeminiTransientUpstreamException ex = assertThrows(GeminiTransientUpstreamException.class,
                () -> client.callStreaming(
                        GeminiRequest.of("insights", "sys", "user", 65_536, 2_048, "gemini-test"),
                        s -> {}),
                "an SSE stream that ends without any finishReason is interrupted → transient, not STOP");
        assertEquals(0, ex.upstreamCode, "no error trailer → upstreamCode 0 (interrupted, no HTTP status)");
    }

    // ── cachekey-include-model: degrade-to-uncached on cross-model 400 ───────

    private static final String CACHE_MISMATCH_400_BODY =
            "{\"error\":{\"code\":400,\"message\":\"Model used by GenerateContent request "
            + "(models/gemini-2.5-flash) and CachedContent (models/gemini-3.5-flash) has to be the same.\","
            + "\"status\":\"INVALID_ARGUMENT\"}}";

    @Test
    void call_cachedContentModelMismatch400_retriesUncachedThenSucceeds() throws Exception {
        // The cross-model cachedContent 400 (job 82d9b1f7) must self-heal: drop the
        // cachedContent and retry once uncached.
        org.mockito.Mockito.when(cacheService.resolveOrCreate(ArgumentMatchers.any(CacheRequest.class)))
                .thenReturn("cachedContents/built-under-other-model");
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(400, CACHE_MISMATCH_400_BODY))
                .thenReturn(fakeJsonResponse(200, candidateBody("recovered", 11, 22, "STOP")));

        GeminiResult r = client.call(
                GeminiRequest.of("insights", "sys-prompt", "dynamic", 1024, 256, "gemini-test")
                        .withCacheRequest(sampleCacheRequest(), "FULL_UNCACHED_PROMPT"));

        assertEquals("recovered", r.text(), "uncached retry must succeed");
        assertFalse(r.cacheHit(), "successful response came from the uncached retry");
        org.mockito.ArgumentCaptor<HttpRequest> cap =
                org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(cap.capture(), bodyHandler());
        String retry = requestBodyOf(cap.getAllValues().get(1));
        assertFalse(retry.contains("cachedContent"), "retry MUST drop cachedContent: " + retry);
        assertTrue(retry.contains("system_instruction"), "retry MUST send the uncached shape: " + retry);
        assertTrue(retry.contains("FULL_UNCACHED_PROMPT"), "retry MUST send the caller's uncached prompt: " + retry);
    }

    @Test
    void call_generic400_staysTerminal_noUncachedRetry() throws Exception {
        // A non-mismatch 400 MUST stay terminal — the uncached retry must not mask
        // unrelated 400s (e.g. a bad request field).
        org.mockito.Mockito.when(cacheService.resolveOrCreate(ArgumentMatchers.any(CacheRequest.class)))
                .thenReturn("cachedContents/abc");
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(400,
                        "{\"error\":{\"code\":400,\"message\":\"Invalid JSON payload: unknown field foo\","
                        + "\"status\":\"INVALID_ARGUMENT\"}}"));

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> client.call(GeminiRequest.of("insights", "sys", "dyn", 1024, 256, "gemini-test")
                        .withCacheRequest(sampleCacheRequest(), "uncached")));
        assertEquals(502, ex.getResponse().getStatus(), "generic 400 → terminal BAD_GATEWAY");
        verify(http, times(1)).send(any(), bodyHandler());
        verify(cacheService, org.mockito.Mockito.never()).delete(ArgumentMatchers.anyString());
    }

    @Test
    void call_mismatch400_persistsOnUncachedRetry_surfacesTerminalNotInternalSignal() throws Exception {
        // Defensive: the private CacheUnusableException must never escape. If
        // the uncached retry somehow ALSO yields a mismatch-shaped 400, surface a
        // terminal BAD_GATEWAY (502) — not the internal RuntimeException signal.
        org.mockito.Mockito.when(cacheService.resolveOrCreate(ArgumentMatchers.any(CacheRequest.class)))
                .thenReturn("cachedContents/abc");
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(400, CACHE_MISMATCH_400_BODY))
                .thenReturn(fakeJsonResponse(400, CACHE_MISMATCH_400_BODY));

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> client.call(GeminiRequest.of("insights", "sys", "dyn", 1024, 256, "gemini-test")
                        .withCacheRequest(sampleCacheRequest(), "uncached")));
        assertEquals(502, ex.getResponse().getStatus(),
                "persisted mismatch must surface as terminal BAD_GATEWAY, not the internal signal");
        verify(http, times(2)).send(any(), bodyHandler());
    }

    @Test
    void call_mismatch400_detectionIsCaseInsensitive() throws Exception {
        // A casing drift in Gemini's error text must still trigger the self-heal.
        org.mockito.Mockito.when(cacheService.resolveOrCreate(ArgumentMatchers.any(CacheRequest.class)))
                .thenReturn("cachedContents/abc");
        String upperBody = "{\"error\":{\"code\":400,\"message\":\"MODEL USED BY GENERATECONTENT REQUEST "
                + "(MODELS/GEMINI-2.5-FLASH) AND CACHEDCONTENT (MODELS/GEMINI-3.5-FLASH) HAS TO BE THE SAME.\"}}";
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(400, upperBody))
                .thenReturn(fakeJsonResponse(200, candidateBody("recovered", 5, 7, "STOP")));

        GeminiResult r = client.call(GeminiRequest.of("insights", "sys", "dyn", 1024, 256, "gemini-test")
                .withCacheRequest(sampleCacheRequest(), "uncached"));
        assertEquals("recovered", r.text(), "uppercase mismatch body must still self-heal");
        verify(http, times(2)).send(any(), bodyHandler());
    }

    @Test
    void callStreaming_cachedContentModelMismatch400_retriesUncachedThenSucceeds() throws Exception {
        org.mockito.Mockito.when(cacheService.resolveOrCreate(ArgumentMatchers.any(CacheRequest.class)))
                .thenReturn("cachedContents/built-under-other-model");
        String sse = "data: {\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"recovered stream\"}]}}],"
                + "\"usageMetadata\":{\"promptTokenCount\":12,\"candidatesTokenCount\":6}}\n\n";
        when(http.send(any(HttpRequest.class), streamingBodyHandler()))
                .thenReturn(fakeStreamingResponse(400, CACHE_MISMATCH_400_BODY))
                .thenReturn(fakeStreamingResponse(200, sse));

        GeminiResult r = client.callStreaming(
                GeminiRequest.of("insights", "sys-prompt", "dynamic", 65_536, 12_288, "gemini-test")
                        .withCacheRequest(sampleCacheRequest(), "FULL_UNCACHED_PROMPT"),
                s -> {});

        assertEquals("recovered stream", r.text(), "uncached streaming retry must succeed");
        assertFalse(r.cacheHit(), "successful stream came from the uncached retry");
        org.mockito.ArgumentCaptor<HttpRequest> cap =
                org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(cap.capture(), streamingBodyHandler());
        String retry = requestBodyOf(cap.getAllValues().get(1));
        assertFalse(retry.contains("cachedContent"), "streaming retry MUST drop cachedContent: " + retry);
        assertTrue(retry.contains("system_instruction"), "streaming retry MUST be uncached: " + retry);
    }

    // ── cache dead-entry self-heal (reaped/deleted cachedContent) ────────────

    private static final String CACHE_NOTFOUND_404_BODY =
            "{\"error\":{\"code\":404,\"message\":\"CachedContent cachedContents/built-under-other-model "
            + "was not found.\",\"status\":\"NOT_FOUND\"}}";
    private static final String CACHE_403_BODY =
            "{\"error\":{\"code\":403,\"message\":\"You do not have permission to access the CachedContent "
            + "cachedContents/abc or it may not exist.\",\"status\":\"PERMISSION_DENIED\"}}";

    @Test
    void call_cachedContentNotFound404_evictsAndRetriesUncachedThenSucceeds() throws Exception {
        // A reaped/deleted cachedContent (404 "not found") must self-heal like the
        // model-mismatch case: evict the stale row + retry once uncached.
        org.mockito.Mockito.when(cacheService.resolveOrCreate(ArgumentMatchers.any(CacheRequest.class)))
                .thenReturn("cachedContents/built-under-other-model");
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(404, CACHE_NOTFOUND_404_BODY))
                .thenReturn(fakeJsonResponse(200, candidateBody("recovered", 11, 22, "STOP")));

        GeminiResult r = client.call(
                GeminiRequest.of("insights", "sys-prompt", "dynamic", 1024, 256, "gemini-test")
                        .withCacheRequest(sampleCacheRequest(), "FULL_UNCACHED_PROMPT"));

        assertEquals("recovered", r.text(), "uncached retry must succeed after a dead-cache 404");
        assertFalse(r.cacheHit());
        verify(cacheService).delete(GeminiCacheService.composeCacheKey(sampleCacheRequest()));
        org.mockito.ArgumentCaptor<HttpRequest> cap =
                org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(2)).send(cap.capture(), bodyHandler());
        String retry = requestBodyOf(cap.getAllValues().get(1));
        assertFalse(retry.contains("cachedContent"), "retry MUST drop cachedContent: " + retry);
    }

    @Test
    void call_cachedContent403Permission_evictsAndRetriesUncachedThenSucceeds() throws Exception {
        org.mockito.Mockito.when(cacheService.resolveOrCreate(ArgumentMatchers.any(CacheRequest.class)))
                .thenReturn("cachedContents/abc");
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(403, CACHE_403_BODY))
                .thenReturn(fakeJsonResponse(200, candidateBody("recovered", 5, 7, "STOP")));

        GeminiResult r = client.call(GeminiRequest.of("insights", "sys", "dyn", 1024, 256, "gemini-test")
                .withCacheRequest(sampleCacheRequest(), "uncached"));

        assertEquals("recovered", r.text(), "403 on the cache resource must self-heal");
        verify(cacheService).delete(GeminiCacheService.composeCacheKey(sampleCacheRequest()));
        verify(http, times(2)).send(any(), bodyHandler());
    }

    @Test
    void callStreaming_cachedContentNotFound404_evictsAndRetriesUncachedThenSucceeds() throws Exception {
        org.mockito.Mockito.when(cacheService.resolveOrCreate(ArgumentMatchers.any(CacheRequest.class)))
                .thenReturn("cachedContents/built-under-other-model");
        String sse = "data: {\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"recovered stream\"}]}}],"
                + "\"usageMetadata\":{\"promptTokenCount\":12,\"candidatesTokenCount\":6}}\n\n";
        when(http.send(any(HttpRequest.class), streamingBodyHandler()))
                .thenReturn(fakeStreamingResponse(404, CACHE_NOTFOUND_404_BODY))
                .thenReturn(fakeStreamingResponse(200, sse));

        GeminiResult r = client.callStreaming(
                GeminiRequest.of("insights", "sys-prompt", "dynamic", 65_536, 12_288, "gemini-test")
                        .withCacheRequest(sampleCacheRequest(), "FULL_UNCACHED_PROMPT"),
                s -> {});

        assertEquals("recovered stream", r.text(), "streaming uncached retry must succeed after dead-cache 404");
        verify(cacheService).delete(GeminiCacheService.composeCacheKey(sampleCacheRequest()));
        verify(http, times(2)).send(any(), streamingBodyHandler());
    }

    @Test
    void call_cacheUnusable_evictThrows_stillRetriesUncached() throws Exception {
        // Eviction is best-effort — a delete failure must NOT block the uncached retry.
        org.mockito.Mockito.when(cacheService.resolveOrCreate(ArgumentMatchers.any(CacheRequest.class)))
                .thenReturn("cachedContents/built-under-other-model");
        org.mockito.Mockito.when(cacheService.delete(ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("DDB down"));
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(404, CACHE_NOTFOUND_404_BODY))
                .thenReturn(fakeJsonResponse(200, candidateBody("recovered", 5, 7, "STOP")));

        GeminiResult r = client.call(GeminiRequest.of("insights", "sys", "dyn", 1024, 256, "gemini-test")
                .withCacheRequest(sampleCacheRequest(), "uncached"));

        assertEquals("recovered", r.text(), "evict failure must not block the uncached retry");
        verify(http, times(2)).send(any(), bodyHandler());
    }

    @Test
    void call_cachedContentExpired_evictsAndRetriesUncachedThenSucceeds() throws Exception {
        // TTL expiry is the MOST COMMON real dead-cache case — the detector must
        // cover "expired"/"no longer available", not just not-found/mismatch.
        org.mockito.Mockito.when(cacheService.resolveOrCreate(ArgumentMatchers.any(CacheRequest.class)))
                .thenReturn("cachedContents/expired-one");
        String expiredBody = "{\"error\":{\"code\":400,\"message\":\"CachedContent cachedContents/expired-one "
                + "has expired and is no longer available.\",\"status\":\"INVALID_ARGUMENT\"}}";
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(400, expiredBody))
                .thenReturn(fakeJsonResponse(200, candidateBody("recovered", 5, 7, "STOP")));

        GeminiResult r = client.call(GeminiRequest.of("insights", "sys", "dyn", 1024, 256, "gemini-test")
                .withCacheRequest(sampleCacheRequest(), "uncached"));

        assertEquals("recovered", r.text(), "an expired cachedContent must self-heal (the common TTL case)");
        verify(cacheService).delete(GeminiCacheService.composeCacheKey(sampleCacheRequest()));
        verify(http, times(2)).send(any(), bodyHandler());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** {@code HttpResponse.BodyHandlers.ofString()} cast for Mockito matchers. */
    @SuppressWarnings("unchecked")
    private static HttpResponse.BodyHandler<String> bodyHandler() {
        return ArgumentMatchers.<HttpResponse.BodyHandler<String>>any();
    }

    /** {@code HttpResponse.BodyHandlers.ofInputStream()} matcher for callStreaming. */
    @SuppressWarnings("unchecked")
    private static HttpResponse.BodyHandler<java.io.InputStream> streamingBodyHandler() {
        return ArgumentMatchers.<HttpResponse.BodyHandler<java.io.InputStream>>any();
    }

    private static HttpResponse<java.io.InputStream> fakeStreamingResponse(int status, String sseBody) {
        java.io.InputStream stream = new java.io.ByteArrayInputStream(
                sseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new FakeStreamingResponse(status, stream,
                HttpHeaders.of(Map.of(), (k, v) -> true));
    }

    /** Minimal HttpResponse<InputStream> stub — only headers(), body(), statusCode() are read. */
    private static final class FakeStreamingResponse implements HttpResponse<java.io.InputStream> {
        private final int status;
        private final java.io.InputStream body;
        private final HttpHeaders headers;

        FakeStreamingResponse(int status, java.io.InputStream body, HttpHeaders headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        @Override public int statusCode() { return status; }
        @Override public HttpHeaders headers() { return headers; }
        @Override public java.io.InputStream body() { return body; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<java.io.InputStream>> previousResponse() { return Optional.empty(); }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("https://fake"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    /**
     * Build a Gemini-shaped response body with one candidate + usageMetadata.
     * Mirrors the fields the SUT actually reads.
     */
    private static String candidateBody(String text, int tokenIn, int tokenOut, String finishReason) {
        return "{"
                + "\"candidates\":[{"
                + "  \"finishReason\":\"" + finishReason + "\","
                + "  \"content\":{\"parts\":[{\"text\":\"" + text + "\"}]}"
                + "}],"
                + "\"usageMetadata\":{"
                + "  \"promptTokenCount\":" + tokenIn + ","
                + "  \"candidatesTokenCount\":" + tokenOut
                + "}"
                + "}";
    }

    private static HttpResponse<String> fakeJsonResponse(int status, String body) {
        return new FakeResponse(status, body, HttpHeaders.of(Map.of(), (k, v) -> true));
    }

    private static HttpResponse<String> fake429Response(long retryAfterSeconds) {
        return new FakeResponse(429, "{}", HttpHeaders.of(
                Map.of("retry-after", List.of(Long.toString(retryAfterSeconds))),
                (k, v) -> true));
    }

    /** Minimal HttpResponse stub — only headers(), body(), statusCode() are read by the SUT. */
    private static final class FakeResponse implements HttpResponse<String> {
        private final int status;
        private final String body;
        private final HttpHeaders headers;

        FakeResponse(int status, String body, HttpHeaders headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        @Override public int statusCode() { return status; }
        @Override public HttpHeaders headers() { return headers; }
        @Override public String body() { return body; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("https://fake"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    // ── CC-5 follow-up: EMF emission parseability ───────────────────────────

    @Test
    void call_emitsEMF_asRawJsonNoLoggerPrefix_parseableByCloudWatch() throws Exception {
        // CRITICAL #1 regression guard: pre-fix the EMF line was emitted via
        // LOG.info(json), which JBoss Logger prefixes with timestamp + level +
        // category — CloudWatch's EMF parser then sees a non-JSON-leading
        // log event and silently drops the metric. Post-fix the emission
        // goes through a raw PrintStream sink (default System.out) so the
        // log event is a bare JSON object.
        //
        // This test asserts (a) the captured bytes parse cleanly as JSON,
        // (b) the JSON has a valid `_aws` envelope, (c) both per-task and
        // stage-only metric definitions are present, (d) dimensions are the
        // lowercase taskType/stage names contracted by plan AC-11.2,
        // (e) the taskType VALUE is canonical (mapped from the kebab
        // request name via TaskMetadata).
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, candidateBody("ok", 100, 50, "STOP")));

        // Use a real task name so the canonical mapping is exercised — "analyze"
        // → TaskMetadata.TASK_ANALYZE ("ANALYZE"). Pre-fix the emitted value
        // would have been "analyze" (the kebab), which doesn't match the
        // AiJobs.taskType "ANALYZE" — dashboards couldn't join the two.
        client.call(GeminiRequest.of("analyze", "sys", "user", 1024, 256, "gemini-test"));

        String emitted = emfBuffer.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
        // (a) Bare JSON — must NOT start with timestamp / log level.
        assertTrue(emitted.startsWith("{"),
                "EMF line must start with '{' — JBoss Logger prefix would make this fail. Got: "
                        + emitted.substring(0, Math.min(80, emitted.length())));
        ObjectMapper m = new ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = m.readTree(emitted);
        // (b) _aws envelope present + well-formed.
        com.fasterxml.jackson.databind.JsonNode aws = root.path("_aws");
        assertFalse(aws.isMissingNode(), "EMF must include the `_aws` envelope: " + emitted);
        assertTrue(aws.path("Timestamp").isNumber(),
                "_aws.Timestamp must be a number (epoch millis): " + aws);
        com.fasterxml.jackson.databind.JsonNode cwMetrics = aws.path("CloudWatchMetrics");
        assertTrue(cwMetrics.isArray() && cwMetrics.size() == 2,
                "CRITICAL #2: must declare TWO metric definitions (per-task dimensioned + "
                        + "stage-only for the alarm). Got: " + cwMetrics);
        // (c) Per-task dimensioned definition.
        com.fasterxml.jackson.databind.JsonNode perTask = cwMetrics.get(0);
        assertEquals("CatalystFlow/AI", perTask.path("Namespace").asText());
        com.fasterxml.jackson.databind.JsonNode perTaskDims = perTask.path("Dimensions").get(0);
        assertEquals("taskType", perTaskDims.get(0).asText(),
                "MEDIUM #2: dimension name must be lowercase taskType per plan AC-11.2");
        assertEquals("stage", perTaskDims.get(1).asText(),
                "MEDIUM #2: dimension name must be lowercase stage per plan AC-11.2");
        // (d) Stage-only definition (alarm consumer).
        com.fasterxml.jackson.databind.JsonNode stageOnly = cwMetrics.get(1);
        com.fasterxml.jackson.databind.JsonNode stageOnlyDims = stageOnly.path("Dimensions").get(0);
        assertEquals(1, stageOnlyDims.size(),
                "CRITICAL #2: alarm-target metric definition must have stage-only dimension "
                        + "so SUM aggregates org-wide. Got: " + stageOnlyDims);
        assertEquals("stage", stageOnlyDims.get(0).asText());
        // (e) Top-level dimension VALUES — taskType is the canonical
        // TaskMetadata.TASK_* constant, NOT the kebab request name. This is
        // what makes CloudWatch dashboards joinable with AiJobs.taskType.
        assertEquals(TaskMetadata.TASK_ANALYZE, root.path("taskType").asText(),
                "MEDIUM #2 follow-up: taskType VALUE must be canonical (ANALYZE), "
                        + "not the kebab request name (analyze). CloudWatch dimension values "
                        + "must match AiJobs.taskType column for dashboards to join correctly.");
        assertEquals("test", root.path("stage").asText());
        // Metric values present.
        assertTrue(root.path("CacheHit").isInt(), "CacheHit must be a number (0|1)");
        assertTrue(root.path("Cacheable").isInt(), "Cacheable must be a number (0|1)");
    }

    @Test
    void call_emitsEMF_canonicalTaskTypeFromCacheRequest_preferredOverKebabMapping() throws Exception {
        // When a CacheRequest is attached, its taskType field IS the canonical
        // value (set by callers from TaskMetadata constants). We must read it
        // directly rather than re-mapping the kebab name. Belt-and-braces: if
        // someone someday adds a task with a kebab name that maps to a wrong
        // task in TaskMetadata.canonicalTaskType, the CacheRequest value
        // overrides correctly.
        org.mockito.Mockito.when(cacheService.resolveOrCreate(org.mockito.ArgumentMatchers.any(CacheRequest.class)))
                .thenReturn(null);  // cache miss — request still emits EMF
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, candidateBody("ok", 100, 50, "STOP")));

        CacheRequest cr = new CacheRequest(TaskMetadata.TASK_MANAGER_GOLDEN,
                "MANAGER_GOLDEN_V2", "DevOps", "Engineer",
                "sys", "extra", "gemini-test", 600);
        client.call(GeminiRequest.of("manager-golden", "sys", "user", 1024, 256, "gemini-test")
                .withCacheRequest(cr, "full-uncached-prompt"));

        String emitted = emfBuffer.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
        com.fasterxml.jackson.databind.JsonNode root = new ObjectMapper().readTree(emitted);
        assertEquals(TaskMetadata.TASK_MANAGER_GOLDEN, root.path("taskType").asText(),
                "When CacheRequest is present, its taskType (already canonical) takes precedence");
    }
}

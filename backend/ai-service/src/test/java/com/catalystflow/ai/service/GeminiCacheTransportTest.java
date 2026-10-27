package com.catalystflow.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CC-2 follow-up — unit tests for {@link GeminiCacheTransport}, the new
 * pair-bean to {@link GeminiClient} that owns the cache-side Gemini HTTP
 * ({@code /cachedContents} create + {@code countTokens} preflight).
 *
 * <p>Replaces and extends the {@code callCachedContents_*} tests that
 * previously lived on {@code GeminiClientTest}. Pattern matches
 * {@link GeminiClientTest}: pure JUnit, mocked HttpClient via package-private
 * field swap.
 */
class GeminiCacheTransportTest {

    private GeminiCacheTransport transport;
    private HttpClient http;

    @BeforeEach
    void setup() {
        http = mock(HttpClient.class);
        transport = new GeminiCacheTransport();
        transport.geminiApiKey = "test-key";
        transport.mapper = new ObjectMapper();
        transport.httpClient = http;
    }

    // ── callCachedContents ────────────────────────────────────────────────────

    @Test
    void callCachedContents_200_returnsCacheNameAndCachedTokens() throws Exception {
        String body = "{"
                + "\"name\":\"cachedContents/abc123\","
                + "\"model\":\"models/gemini-2.5-flash\","
                + "\"ttl\":\"3600s\","
                + "\"usageMetadata\":{\"totalTokenCount\":2500}"
                + "}";
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, body));

        GeminiCacheTransport.CacheCreateResult result =
                transport.callCachedContents("gemini-2.5-flash", "long cacheable content...", "", 3600);

        assertNotNull(result);
        assertEquals("cachedContents/abc123", result.cacheName());
        assertEquals(Integer.valueOf(2500), result.cachedTokens());
    }

    @Test
    void callCachedContents_4xx_returnsNull() throws Exception {
        // Most common 4xx: Gemini rejects content below min_total_token_count.
        // (Service-layer preflight should prevent this path normally; the
        // null fallback here is the belt-and-braces for stricter-than-1500
        // per-model minima.)
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(400,
                        "{\"error\":{\"message\":\"min_total_token_count not met\"}}"));

        GeminiCacheTransport.CacheCreateResult result =
                transport.callCachedContents("gemini-2.5-flash", "too short", "", 3600);

        assertNull(result, "4xx must surface as null, not throw");
    }

    @Test
    void callCachedContents_ioFailure_returnsNull() throws Exception {
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenThrow(new IOException("connection reset"));

        GeminiCacheTransport.CacheCreateResult result =
                transport.callCachedContents("gemini-2.5-flash", "content", "", 3600);

        assertNull(result);
    }

    @Test
    void callCachedContents_timeout_returnsNull() throws Exception {
        // Cache-side timeout uses the 30s budget (vs generateContent's 500s).
        // Same degrade-to-null contract on exhaustion.
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenThrow(new HttpTimeoutException("30s"));

        GeminiCacheTransport.CacheCreateResult result =
                transport.callCachedContents("gemini-2.5-flash", "content", "", 3600);

        assertNull(result);
    }

    @Test
    void callCachedContents_200WithoutName_returnsNull() throws Exception {
        // Defensive: schema says 200 must include "name", but if Gemini drifts
        // we'd rather degrade than persist a blank cacheName into DDB.
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, "{\"model\":\"models/gemini-2.5-flash\"}"));

        GeminiCacheTransport.CacheCreateResult result =
                transport.callCachedContents("gemini-2.5-flash", "content", "", 3600);

        assertNull(result, "Missing 'name' field must surface as null");
    }

    @Test
    void callCachedContents_modelWithoutPrefix_normalisedToModelsPrefix() throws Exception {
        // The cachedContents API requires "models/<id>", unlike generateContent
        // which uses the URL-path model. Caller may pass either form.
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, "{\"name\":\"cachedContents/ok\"}"));

        transport.callCachedContents("gemini-2.5-flash", "content", "", 3600);

        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(cap.capture(), any());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/cachedContents",
                cap.getValue().uri().toString());
    }

    @Test
    void callCachedContents_modelWithPrefix_passesThroughUnchanged() throws Exception {
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, "{\"name\":\"cachedContents/ok2\"}"));

        GeminiCacheTransport.CacheCreateResult result =
                transport.callCachedContents("models/gemini-2.5-flash", "content", "", 3600);

        assertNotNull(result);
        assertEquals("cachedContents/ok2", result.cacheName());
    }

    // ── MEDIUM #2 regression: systemInstruction + contents split ─────────────

    @Test
    void callCachedContents_bodyShape_systemPromptToSystemInstruction_extraPrefixToContents()
            throws Exception {
        // CC-4a MEDIUM #2 follow-up. Pre-fix this method concatenated
        // systemPrompt + extraPrefix into one user-role part — Gemini then
        // read the system prompt as user content, breaking the role
        // distinction the downstream generateContent call relies on.
        //
        // Post-fix the body must place systemPrompt under systemInstruction
        // and extraPrefix under contents[0] (role=user). This test pins the
        // shape: any future refactor that re-flattens the two will fail here
        // instead of silently corrupting cached prefixes in production.
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, "{\"name\":\"cachedContents/shape-ok\"}"));

        transport.callCachedContents("gemini-2.5-flash",
                "SYS_PROMPT_TEXT", "EXTRA_PREFIX_TEXT", 3600);

        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(cap.capture(), any());
        String sent = capturedRequestBody(cap.getValue());

        // systemInstruction carries the system prompt — role distinction preserved.
        assertTrue(sent.contains("\"systemInstruction\""),
                "Body must include systemInstruction field: " + sent);
        assertTrue(sent.contains("SYS_PROMPT_TEXT"),
                "systemPrompt text must appear inside the body: " + sent);
        int sysInstrIdx = sent.indexOf("systemInstruction");
        int sysPromptIdx = sent.indexOf("SYS_PROMPT_TEXT");
        assertTrue(sysPromptIdx > sysInstrIdx,
                "SYS_PROMPT_TEXT must appear AFTER systemInstruction in the JSON (i.e., it's the systemInstruction value): "
                        + sent);

        // extraPrefix goes under contents[0] with role=user — the cacheable
        // user-role preamble that Gemini prepends to the per-call user message.
        assertTrue(sent.contains("\"contents\""),
                "Body must include contents field for the user-role prefix: " + sent);
        assertTrue(sent.contains("EXTRA_PREFIX_TEXT"),
                "extraPrefix text must appear inside the body: " + sent);
        assertTrue(sent.contains("\"role\":\"user\""),
                "extraPrefix must be sent with role=user: " + sent);

        // The two texts must not be glued together — proves the split actually happened.
        assertFalse(sent.contains("SYS_PROMPT_TEXTEXTRA_PREFIX_TEXT"),
                "Body must NOT concatenate systemPrompt and extraPrefix into one string: " + sent);
    }

    @Test
    void callCachedContents_emptyExtraPrefix_omitsContentsBlock() throws Exception {
        // Edge: when extraPrefix is empty/blank the body must NOT include an
        // empty contents block. Gemini rejects cachedContents with an empty
        // contents array (validation error from the API), so the bean has to
        // emit systemInstruction-only when extraPrefix has no payload.
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, "{\"name\":\"cachedContents/sysonly\"}"));

        transport.callCachedContents("gemini-2.5-flash", "SYS_ONLY", "", 3600);

        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(cap.capture(), any());
        String sent = capturedRequestBody(cap.getValue());

        assertTrue(sent.contains("\"systemInstruction\""),
                "systemInstruction must still be present: " + sent);
        assertFalse(sent.contains("\"contents\""),
                "Empty extraPrefix must NOT emit a contents block (Gemini rejects empty contents): "
                        + sent);
    }

    // Helper for body-shape assertions. Mirrors the implementation in
    // GeminiClientTest — subscribes to the HttpRequest.BodyPublisher to
    // accumulate the bytes the SUT serialised.
    private static String capturedRequestBody(HttpRequest sent) {
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

    // ── countTokens ──────────────────────────────────────────────────────────

    @Test
    void countTokens_200_returnsTotalTokens() throws Exception {
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, "{\"totalTokens\":2750}"));

        Integer tokens = transport.countTokens("gemini-2.5-flash", "content of moderate size");

        assertEquals(Integer.valueOf(2750), tokens);
    }

    @Test
    void countTokens_hitsCorrectModelEndpoint() throws Exception {
        // countTokens uses /v1beta/models/{model}:countTokens — DIFFERENT URL
        // shape from cachedContents (which uses /v1beta/cachedContents).
        // Regression guard: easy to typo and accidentally hit the wrong endpoint.
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, "{\"totalTokens\":1700}"));

        transport.countTokens("gemini-2.5-flash", "content");

        ArgumentCaptor<HttpRequest> cap = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(cap.capture(), any());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:countTokens",
                cap.getValue().uri().toString(),
                "countTokens must hit /models/<id>:countTokens, not /cachedContents");
    }

    @Test
    void countTokens_4xx_returnsNull() throws Exception {
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(400, "{\"error\":\"bad request\"}"));

        Integer tokens = transport.countTokens("gemini-2.5-flash", "content");

        assertNull(tokens, "4xx must surface as null");
    }

    @Test
    void countTokens_200WithoutTotalTokens_returnsNull() throws Exception {
        // Defensive: schema says 200 must include "totalTokens". If absent,
        // service can't make the preflight decision — better to degrade.
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenReturn(fakeJsonResponse(200, "{\"model\":\"models/gemini-2.5-flash\"}"));

        Integer tokens = transport.countTokens("gemini-2.5-flash", "content");

        assertNull(tokens);
    }

    @Test
    void countTokens_ioFailure_returnsNull() throws Exception {
        when(http.send(any(HttpRequest.class), bodyHandler()))
                .thenThrow(new IOException("connection reset"));

        Integer tokens = transport.countTokens("gemini-2.5-flash", "content");

        assertNull(tokens);
    }

    // ── helpers (same shape as GeminiClientTest) ─────────────────────────────

    @SuppressWarnings("unchecked")
    private static HttpResponse.BodyHandler<String> bodyHandler() {
        return ArgumentMatchers.<HttpResponse.BodyHandler<String>>any();
    }

    private static HttpResponse<String> fakeJsonResponse(int status, String body) {
        return new FakeResponse(status, body, HttpHeaders.of(Map.of(), (k, v) -> true));
    }

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
}

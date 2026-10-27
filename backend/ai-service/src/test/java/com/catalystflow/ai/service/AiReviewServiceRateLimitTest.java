package com.catalystflow.ai.service;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for the 429 / RATE_LIMITED contract introduced with the
 * AiWorker fan-out (BatchSize 1→10, reserved 5→25, 2026-05-20).
 *
 * <p>Two surfaces under test:
 * <ol>
 *   <li>{@link AiReviewService#parseRetryAfterMs} — header parsing</li>
 *   <li>{@link AiReviewService#classifyError} — quota-error tagging</li>
 * </ol>
 *
 * <p>Pure unit tests — no Quarkus bootstrap, no Gemini mocking. The retry-loop
 * behaviour itself can't be unit-tested without mocking the JDK HttpClient
 * (an integration concern tracked under the GeminiClient-extraction claw-back
 * plan in ai-service/pom.xml).
 */
class AiReviewServiceRateLimitTest {

    // ── parseRetryAfterMs ────────────────────────────────────────────────────

    @Test
    void parseRetryAfter_seconds_returnsMillis() {
        HttpResponse<String> resp = fakeResponse(429, "12");
        OptionalLong result = AiReviewService.parseRetryAfterMs(resp);
        assertTrue(result.isPresent());
        assertEquals(12_000L, result.getAsLong());
    }

    @Test
    void parseRetryAfter_zero_returnsZeroMillis() {
        HttpResponse<String> resp = fakeResponse(429, "0");
        OptionalLong result = AiReviewService.parseRetryAfterMs(resp);
        assertTrue(result.isPresent());
        assertEquals(0L, result.getAsLong());
    }

    @Test
    void parseRetryAfter_missingHeader_returnsEmpty() {
        HttpResponse<String> resp = fakeResponseNoRetryAfter(429);
        assertFalse(AiReviewService.parseRetryAfterMs(resp).isPresent());
    }

    @Test
    void parseRetryAfter_unparseableString_returnsEmpty() {
        HttpResponse<String> resp = fakeResponse(429, "later please");
        assertFalse(AiReviewService.parseRetryAfterMs(resp).isPresent());
    }

    @Test
    void parseRetryAfter_negativeSeconds_returnsEmpty() {
        // Negative values are nonsense per spec — treat as missing header.
        HttpResponse<String> resp = fakeResponse(429, "-5");
        assertFalse(AiReviewService.parseRetryAfterMs(resp).isPresent());
    }

    @Test
    void parseRetryAfter_httpDateInPast_returnsZero() {
        HttpResponse<String> resp = fakeResponse(429, "Wed, 01 Jan 2020 00:00:00 GMT");
        OptionalLong result = AiReviewService.parseRetryAfterMs(resp);
        assertTrue(result.isPresent(), "HTTP-date past should yield Optional.of(0), not empty");
        assertEquals(0L, result.getAsLong());
    }

    // ── classifyError — RATE_LIMITED bucket ──────────────────────────────────

    @Test
    void classify_rateLimitedException_yieldsRateLimitedTag() {
        WebApplicationException ex = new WebApplicationException(
                "RATE_LIMITED: Gemini quota exceeded after 3 attempts (Retry-After=2000ms)",
                Response.Status.TOO_MANY_REQUESTS);

        String result = AiReviewService.classifyError(ex);

        assertTrue(result.startsWith("RATE_LIMITED:"),
                "Expected RATE_LIMITED bucket, got: " + result);
        assertTrue(result.contains("quota exceeded"));
    }

    @Test
    void classify_429WithoutPrefix_stillYieldsRateLimited() {
        // Defensive — even if a future code path throws 429 without the explicit
        // message prefix, the status code should still drop it into RATE_LIMITED
        // rather than the generic UPSTREAM_4XX bucket.
        WebApplicationException ex = new WebApplicationException(
                "Some other 429 message", Response.Status.TOO_MANY_REQUESTS);

        String result = AiReviewService.classifyError(ex);

        assertTrue(result.startsWith("RATE_LIMITED:"),
                "Status-code-only 429 should still classify as RATE_LIMITED, got: " + result);
    }

    @Test
    void classify_wrappedRuntimeException_withRateLimitedPrefix_yieldsRateLimited() {
        // ManagerGuidanceService throws plain RuntimeException with the RATE_LIMITED
        // prefix. The outer catch in runJobInProcess wraps in another RuntimeException.
        // Both must classify correctly.
        RuntimeException inner = new RuntimeException(
                "RATE_LIMITED: Gemini quota exceeded after 3 attempts (Retry-After=4000ms)");

        String result = AiReviewService.classifyError(inner);
        assertTrue(result.startsWith("RATE_LIMITED:"),
                "Direct RuntimeException with RATE_LIMITED prefix must classify correctly: " + result);
    }

    // ── RS-1 regression: upstream-code parsing fixes BAD_GATEWAY wrapper drift ──

    @Test
    void classify_geminiClientWrappedUpstream400_yieldsUpstream4xx_notUpstream5xx() {
        // GeminiClient (RS-1) wraps every non-200 / non-429 upstream response
        // in WebApplicationException(BAD_GATEWAY) with message
        // "AI service error (upstream <code>)". Before the RS-1 classifyError
        // fix, this would have bucketed as UPSTREAM_5XX (502) because the
        // WAE's status is BAD_GATEWAY=502 regardless of the real upstream code.
        // After the fix, the real 400 is extracted from the message.
        WebApplicationException wae = new WebApplicationException(
                "AI service error (upstream 400)",
                jakarta.ws.rs.core.Response.Status.BAD_GATEWAY);

        String result = AiReviewService.classifyError(wae);

        assertTrue(result.startsWith("UPSTREAM_4XX (400)"),
                "Real upstream 400 must bucket as UPSTREAM_4XX (400), not UPSTREAM_5XX (502): " + result);
    }

    @Test
    void classify_geminiClientWrappedUpstream503_yieldsUpstream5xx_withRealCode() {
        // Same drift fix — upstream 503 must report as UPSTREAM_5XX (503), not (502).
        WebApplicationException wae = new WebApplicationException(
                "AI service error (upstream 503)",
                jakarta.ws.rs.core.Response.Status.BAD_GATEWAY);

        String result = AiReviewService.classifyError(wae);

        assertTrue(result.startsWith("UPSTREAM_5XX (503)"),
                "Upstream 503 must report code 503, not the BAD_GATEWAY wrapper code 502: " + result);
    }

    @Test
    void classify_transientUpstreamException_5xx_yieldsUpstream5xxWithRealCode() {
        // The worker calls classifyError on the transient exception when it exhausts
        // SQS receives, to build the terminal FAILED tag.
        GeminiTransientUpstreamException e =
                new GeminiTransientUpstreamException("AI service error (upstream 503)", 503, 0L);

        String result = AiReviewService.classifyError(e);

        assertTrue(result.startsWith("UPSTREAM_5XX (503)"),
                "Transient 503 must tag UPSTREAM_5XX (503): " + result);
    }

    @Test
    void classify_transientUpstreamException_ioCause_yieldsUnavailable() {
        // upstreamCode 0 signals a post-retry IO failure, not an HTTP status.
        GeminiTransientUpstreamException e =
                new GeminiTransientUpstreamException("AI service unavailable (IO: IOException)", 0, 0L);

        String result = AiReviewService.classifyError(e);

        assertTrue(result.startsWith("UNAVAILABLE"),
                "Transient IO failure must tag UNAVAILABLE, not a fake 5xx: " + result);
    }

    @Test
    void classify_geminiClientWrappedUpstream504_yieldsTimeout() {
        // Special-case: real upstream 504 should be TIMEOUT, not UPSTREAM_5XX.
        WebApplicationException wae = new WebApplicationException(
                "AI service error (upstream 504)",
                jakarta.ws.rs.core.Response.Status.BAD_GATEWAY);

        String result = AiReviewService.classifyError(wae);

        assertTrue(result.startsWith("TIMEOUT:"),
                "Upstream 504 should bucket as TIMEOUT: " + result);
    }

    @Test
    void classify_genericUpstream4xx_stillYieldsUpstream4xx_notRateLimited() {
        // Regression guard: only 429 should classify as RATE_LIMITED. Other 4xx
        // codes (400/401/403/404) keep the UPSTREAM_4XX bucket.
        WebApplicationException ex = new WebApplicationException(
                "AI service error (upstream 400)", Response.Status.BAD_REQUEST);

        String result = AiReviewService.classifyError(ex);
        assertTrue(result.startsWith("UPSTREAM_4XX"),
                "Non-429 4xx must NOT be RATE_LIMITED, got: " + result);
        assertFalse(result.startsWith("RATE_LIMITED"),
                "Non-429 4xx must NOT be RATE_LIMITED, got: " + result);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static HttpResponse<String> fakeResponse(int status, String retryAfterValue) {
        return new FakeResponse(status, HttpHeaders.of(
                Map.of("retry-after", List.of(retryAfterValue)),
                (k, v) -> true));
    }

    private static HttpResponse<String> fakeResponseNoRetryAfter(int status) {
        return new FakeResponse(status, HttpHeaders.of(Map.of(), (k, v) -> true));
    }

    /** Minimal HttpResponse stub — only headers() and statusCode() are needed by the SUT. */
    private static final class FakeResponse implements HttpResponse<String> {
        private final int status;
        private final HttpHeaders headers;

        FakeResponse(int status, HttpHeaders headers) {
            this.status = status;
            this.headers = headers;
        }

        @Override public int statusCode() { return status; }
        @Override public HttpHeaders headers() { return headers; }
        @Override public String body() { return ""; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("https://fake"); }
        @Override public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
    }
}

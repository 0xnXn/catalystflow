package com.catalystflow.ai.service;

import com.catalystflow.ai.repository.AiJobRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Worker-level proof that a parse failure AFTER a successful Gemini 200 OK
 * call still persists token telemetry on the FAILED AiJobs row.
 *
 * <p>Pre-fix, parseResponse / parseInsightsResponse threw
 * {@link jakarta.ws.rs.WebApplicationException} on bad JSON. The cause chain
 * carried no Gemini telemetry, so {@code runJobInProcess} fell into the
 * generic {@code catch (Exception)} → {@code updateFailed(jobId, errorMessage)}
 * branch with no tokens / model / promptVersion. The Gemini call cost was
 * invisible in cost dashboards even though the tokens were burnt.
 *
 * <p>Post-fix, parse failures are wrapped in
 * {@link GeminiTelemetryException} carrying the {@code GeminiResult}
 * metadata so the dedicated catch branch in runJobInProcess persists
 * tokenIn / tokenOut / modelUsed / promptVersion onto the FAILED row.
 *
 * <p>Pure {@code @QuarkusTest} with two {@code @InjectMock}-replaced beans
 * (geminiClient + aiJobRepository). The {@code NonMockProfile} disables
 * both {@code ai-review.mock} and {@code coaching.mock} so the real
 * dispatcher logic runs against the mocked Gemini.
 */
@QuarkusTest
@TestProfile(AiReviewServiceParseFailurePersistenceTest.NonMockProfile.class)
class AiReviewServiceParseFailurePersistenceTest {

    public static class NonMockProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "ai-review.mock", "false",
                    "coaching.mock", "false",
                    // Disable the local fallback so parse-after-200 failures
                    // actually surface as FAILED rows. With fallback enabled
                    // the row would be COMPLETED with a local stub (separate
                    // UX concern; cost-attribution leak there is a follow-up).
                    "ai-review.provider-fallback", "false"
            );
        }
    }

    @Inject
    AiReviewService service;

    @InjectMock
    AiJobRepository repo;

    @InjectMock
    GeminiClient geminiClient;

    @BeforeEach
    void setUp() {
        reset(repo, geminiClient);
    }

    // ── ANALYZE: parse failure after 200 OK persists full telemetry ──────────

    @Test
    void runJobInProcess_analyze_parseFailureAfter200_persistsFullTelemetry() {
        // Gemini "succeeds" (200 OK + cached prefix of 321 tokens) but returns
        // non-JSON. Tokens AND cache attribution must flow through
        // GeminiTelemetryException to the catch branch and onto the FAILED row.
        //
        // CC-5 follow-up: cacheHit=true + cachedTokens=321 are SPECIFIC values
        // (not nulls behind any() matchers). The reviewer caught that the
        // previous version of this test used new GeminiResult(..., false, null)
        // and verify(repo).updateFailed(..., any(), any(), any()) — the parse-
        // after-200 regression (5-arg ctor dropping cacheHit/cachedTokens)
        // would come back silently and the test would stay green. This
        // version captures the exact values and asserts they round-trip
        // through the 9-arg updateFailed.
        //
        // The fallback path in AiReviewService has providerFallbackEnabled=true
        // for ANALYZE by default in the test profile — and would normally
        // recover to COMPLETED. This test specifically targets the
        // providerFallbackEnabled=false path: when fallback is OFF, the
        // exception escapes runJobInProcess's analyze branch and lands in
        // the catch(GeminiTelemetryException) at line 396 → updateFailed.
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(new GeminiResult("not json", 500, 200, "STOP", "gemini-test",
                        true, 321));

        when(repo.getRequestJson("an-bad-json")).thenReturn(validAnalyzeEnvelope());
        when(repo.getTaskType("an-bad-json")).thenReturn("ANALYZE");

        service.runJobInProcess("an-bad-json");

        verify(repo).updateProcessing("an-bad-json");

        ArgumentCaptor<String> errCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> inCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> outCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> modelCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> promptVerCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> retriesCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> cacheHitCap = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Integer> cachedTokensCap = ArgumentCaptor.forClass(Integer.class);

        verify(repo).updateFailed(eq("an-bad-json"), errCap.capture(),
                inCap.capture(), outCap.capture(),
                modelCap.capture(), promptVerCap.capture(),
                retriesCap.capture(),
                cacheHitCap.capture(), cachedTokensCap.capture());

        assertTrue(errCap.getValue().startsWith("MALFORMED"),
                "Parse failure must classify as MALFORMED: " + errCap.getValue());
        assertEquals(Integer.valueOf(500), inCap.getValue(),
                "tokenIn from the 200 OK Gemini call must persist on the FAILED row");
        assertEquals(Integer.valueOf(200), outCap.getValue());
        assertEquals("gemini-test", modelCap.getValue(),
                "modelUsed from the GeminiResult must persist (not null)");
        assertEquals("ANALYZE_V2", promptVerCap.getValue());
        // CC-5 follow-up: cache attribution must round-trip through the
        // 7-arg GeminiTelemetryException ctor and the catch block's 9-arg
        // updateFailed. Pre-fix the 5-arg ctor dropped these.
        assertEquals(Boolean.TRUE, cacheHitCap.getValue(),
                "cacheHit must propagate through parse-after-200 → "
                        + "GeminiTelemetryException → updateFailed");
        assertEquals(Integer.valueOf(321), cachedTokensCap.getValue(),
                "cachedTokens must propagate with the exact value — cached prefix tokens "
                        + "were genuinely consumed on the 200 OK call");
        verify(repo, never()).updateCompleted(anyString(), anyString(),
                any(), any(), any(), any(), any());
    }

    // ── INSIGHTS: parse failure after 200 OK persists full telemetry ─────────

    @Test
    void runJobInProcess_insights_parseFailureAfter200_persistsFullTelemetry() {
        // Same reasoning for the INSIGHTS path. Array-shape summary is the
        // specific drift that survived schema enforcement in early Gemini 2.5
        // — the test pins that scenario now produces a FAILED row with full
        // token + cache attribution rather than null telemetry.
        //
        // CC-5 follow-up: cacheHit=true + cachedTokens=480 are SPECIFIC values
        // captured + asserted (not any() matchers). INSIGHTS has no provider
        // fallback so the exception goes straight to runJobInProcess →
        // catch(GeminiTelemetryException) → 9-arg updateFailed. Pre-fix the
        // 5-arg ctor in generateInsightsWithUsage dropped cacheHit/cachedTokens
        // and the FAILED row attribution was incomplete for cached calls.
        String arrayShape = "{"
                + "\"summary\":[\"- Core impact: shipped feature\"],"
                + "\"goldenMirror\":\"TECHNOLOGY / CORE SKILLS\\n\\nDelivered.\""
                + "}";
        // INSIGHTS in the SQS-driven runJobInProcess path now routes through
        // callStreaming (jobId-present overload). Mock that — the legacy call()
        // path is reserved for sync REST callers.
        when(geminiClient.callStreaming(any(GeminiRequest.class), any()))
                .thenReturn(new GeminiResult(arrayShape, 1200, 800, "STOP", "gemini-test",
                        true, 480));

        when(repo.getRequestJson("ins-bad-shape")).thenReturn(validInsightsEnvelope());
        when(repo.getTaskType("ins-bad-shape")).thenReturn("INSIGHTS");

        service.runJobInProcess("ins-bad-shape");

        ArgumentCaptor<String> errCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> inCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> outCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> modelCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> promptVerCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> retriesCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> cacheHitCap = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Integer> cachedTokensCap = ArgumentCaptor.forClass(Integer.class);

        verify(repo).updateFailed(eq("ins-bad-shape"), errCap.capture(),
                inCap.capture(), outCap.capture(),
                modelCap.capture(), promptVerCap.capture(),
                retriesCap.capture(),
                cacheHitCap.capture(), cachedTokensCap.capture());

        assertTrue(errCap.getValue().startsWith("MALFORMED"),
                "Parse failure must classify as MALFORMED: " + errCap.getValue());
        assertEquals(Integer.valueOf(1200), inCap.getValue());
        assertEquals(Integer.valueOf(800), outCap.getValue());
        assertEquals("gemini-test", modelCap.getValue());
        assertEquals("INSIGHTS_V2", promptVerCap.getValue());
        // CC-5 follow-up: cache attribution must round-trip from GeminiResult
        // through the 7-arg GeminiTelemetryException ctor (in
        // generateInsightsWithUsage's parseEx wrap) and onto the 9-arg
        // updateFailed call. Pre-fix the 5-arg ctor dropped both fields and
        // INSIGHTS FAILED rows had no cache attribution.
        assertEquals(Boolean.TRUE, cacheHitCap.getValue(),
                "cacheHit must propagate through INSIGHTS parse-after-200 → "
                        + "GeminiTelemetryException → updateFailed");
        assertEquals(Integer.valueOf(480), cachedTokensCap.getValue(),
                "cachedTokens must propagate with the exact value");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String validAnalyzeEnvelope() {
        return "{"
                + "\"discipline\":\"Software & DevOps Engineering\","
                + "\"seniority\":\"Engineer\","
                + "\"questions\":[{\"questionId\":\"q1\","
                + "  \"questionText\":\"Describe a win.\","
                + "  \"answer\":\"Shipped a thing.\"}]"
                + "}";
    }

    private static String validInsightsEnvelope() {
        return "{"
                + "\"discipline\":\"Software & DevOps Engineering\","
                + "\"seniority\":\"Engineer\","
                + "\"questions\":[{\"questionId\":\"q1\","
                + "  \"questionText\":\"Describe a win.\","
                + "  \"answer\":\"Shipped a thing.\"}]"
                + "}";
    }
}

package com.catalystflow.ai.service;

import com.catalystflow.ai.repository.AiJobRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Worker-level proof that the ANALYZE provider-fallback (COMPLETED) path
 * persists Gemini token telemetry recovered from
 * {@link GeminiTelemetryException}.
 *
 * <p>Pre-fix the fallback catch returned {@code AnalyzeResult(stub, null,
 * null)} so the COMPLETED AiJobs row had no cost attribution despite the
 * Gemini 200 OK call actually burning tokens. The companion
 * {@link AiReviewServiceParseFailurePersistenceTest} covers only the
 * fallback-DISABLED branch (FAILED row); this class covers the
 * fallback-ENABLED branch which is the production default.
 *
 * <p>Two scenarios:
 * <ol>
 *   <li>Parse-after-200 failure → fallback fires, COMPLETED row carries
 *       recovered tokens from the wrapped {@code GeminiTelemetryException}.</li>
 *   <li>Pure network/upstream failure → fallback fires, COMPLETED row
 *       has null tokens (no Gemini call ever produced metadata).</li>
 * </ol>
 */
@QuarkusTest
@TestProfile(AiReviewServiceAnalyzeFallbackPersistenceTest.FallbackEnabledProfile.class)
class AiReviewServiceAnalyzeFallbackPersistenceTest {

    public static class FallbackEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // Production default — explicit here so the test is self-documenting.
            return Map.of(
                    "ai-review.mock", "false",
                    "coaching.mock", "false",
                    "ai-review.provider-fallback", "true"
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

    // ── Parse-after-200 → fallback completion preserves Gemini telemetry ─────

    @Test
    void runJobInProcess_analyze_parseFailureAfter200_fallbackCompletes_withRecoveredTelemetry() {
        // Gemini "succeeds" (200 OK, 500/200 tokens, CACHED with 321 cached tokens)
        // but body is unparseable. analyzeWithUsage wraps as
        // GeminiTelemetryException, outer catch recognises the type and recovers
        // tokens + cache attribution onto the local fallback AnalyzeResult.
        // runJobInProcess sees a successful AnalyzeResult and marks COMPLETED
        // with the local stub content + real Gemini tokens + cache attribution.
        //
        // CC-5 follow-up: cacheHit=true + cachedTokens=321 are SPECIFIC values
        // (not nulls behind any() matchers). Pre-fix the parse-after-200 path
        // used the 5-arg GeminiTelemetryException ctor which dropped these
        // fields, then the fallback hard-coded null/null on AnalyzeResult.
        // The reviewer caught that the previous version of this test used
        // any() matchers — the regression could come back silently and the
        // test would stay green. This version captures the exact values and
        // asserts cacheHit=true / cachedTokens=321 reach updateCompleted.
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(new GeminiResult("not json at all", 500, 200, "STOP", "gemini-test",
                        true, 321));

        when(repo.getRequestJson("an-fb-bad-json")).thenReturn(validAnalyzeEnvelope());
        when(repo.getTaskType("an-fb-bad-json")).thenReturn("ANALYZE");

        service.runJobInProcess("an-fb-bad-json");

        verify(repo).updateProcessing("an-fb-bad-json");

        ArgumentCaptor<String> resultCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> inCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> outCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> modelCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> promptVerCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> retriesCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Boolean> cacheHitCap = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Integer> cachedTokensCap = ArgumentCaptor.forClass(Integer.class);

        verify(repo).updateCompleted(eq("an-fb-bad-json"), resultCap.capture(),
                inCap.capture(), outCap.capture(),
                modelCap.capture(), promptVerCap.capture(), retriesCap.capture(),
                cacheHitCap.capture(), cachedTokensCap.capture());

        assertTrue(resultCap.getValue().contains("suggestions"),
                "Fallback result must still serialise as an AiReviewResponse: "
                        + resultCap.getValue());
        assertEquals(Integer.valueOf(500), inCap.getValue(),
                "tokenIn from the 200 OK Gemini call must persist on the COMPLETED "
                        + "fallback row — pre-fix this was null and the cost was hidden");
        assertEquals(Integer.valueOf(200), outCap.getValue(),
                "tokenOut must persist on the fallback row");
        // modelUsed in updateCompleted comes from the configured geminiModel
        // (not the exception) — runJobInProcess passes geminiModel directly
        // for ANALYZE/INSIGHTS completed rows. Just assert non-null.
        assertEquals("ANALYZE_V2", promptVerCap.getValue(),
                "Prompt slim-down §2.5 bumped ANALYZE_V1 → V2");
        // CC-5 follow-up: cache attribution must round-trip through the
        // GeminiTelemetryException → AnalyzeResult → updateCompleted chain.
        assertEquals(Boolean.TRUE, cacheHitCap.getValue(),
                "cacheHit must propagate from GeminiResult through the 7-arg "
                        + "GeminiTelemetryException ctor and the recovery branch onto the "
                        + "COMPLETED fallback row — pre-fix the 5-arg ctor dropped it and "
                        + "the fallback hard-coded null");
        assertEquals(Integer.valueOf(321), cachedTokensCap.getValue(),
                "cachedTokens must propagate identically — cached prefix tokens were genuinely "
                        + "consumed on the 200 OK call and cost attribution must record them");
        verify(repo, never()).updateFailed(eq("an-fb-bad-json"), anyString());
    }

    // ── Network failure → fallback completion has null telemetry ─────────────

    @Test
    void runJobInProcess_analyze_networkFailure_fallbackCompletes_withNullTelemetry() {
        // Symmetric guard: if the WAE came from GeminiClient before any 200 OK
        // (e.g. SERVICE_UNAVAILABLE after retry exhaustion), there's no
        // GeminiResult to recover from. Tokens stay null. The row is still
        // COMPLETED with the local fallback content (UX preserve).
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenThrow(new WebApplicationException("AI service unavailable", 503));

        when(repo.getRequestJson("an-fb-network")).thenReturn(validAnalyzeEnvelope());
        when(repo.getTaskType("an-fb-network")).thenReturn("ANALYZE");

        service.runJobInProcess("an-fb-network");

        ArgumentCaptor<Integer> inCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> outCap = ArgumentCaptor.forClass(Integer.class);

        verify(repo).updateCompleted(eq("an-fb-network"), anyString(),
                inCap.capture(), outCap.capture(),
                any(), any(), any(), any(), any());

        assertEquals(null, inCap.getValue(),
                "No Gemini telemetry to recover — tokenIn must stay null on "
                        + "network-failure fallback completion");
        assertEquals(null, outCap.getValue());
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
}

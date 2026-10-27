package com.catalystflow.ai.service;

import com.catalystflow.ai.model.SchemaInvalidException;
import com.catalystflow.ai.repository.AiJobRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RS-4 follow-up (code-review): proves the worker/dispatcher boundary
 * actually persists telemetry on the SCHEMA_INVALID path.
 *
 * <p>The unit test {@code ManagerGuidanceServiceQuestionSuggestSchemaTest}
 * only asserts the exception escapes from {@code suggestForQuestion}. AC-4.4
 * requires "worker writes FAILED + token telemetry" — that's the
 * {@code AiReviewService.runJobInProcess} catch chain calling the 7-arg
 * {@code AiJobRepository.updateFailed}. This test exercises that path
 * end-to-end with Quarkus CDI wiring + Mockito stubs on the two relevant
 * collaborators.
 *
 * <p>Distinct from {@code AiReviewServiceManagerDispatchTest} because that
 * class uses {@code coaching.mock=true} to make ManagerGuidanceService
 * return a real deterministic stub. To exercise the exception path we need
 * the service to actually throw — accomplished via {@code @InjectMock}
 * which fully replaces the bean.
 */
@QuarkusTest
@TestProfile(AiReviewServiceManagerQuestionSuggestPersistenceTest.NonMockProfile.class)
class AiReviewServiceManagerQuestionSuggestPersistenceTest {

    /**
     * Disables the test-resources {@code coaching.mock=true} default so
     * runJobInProcess takes the real (non-mock) branch and actually invokes
     * the injected {@code ManagerGuidanceService} bean.
     */
    public static class NonMockProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "ai-review.mock", "false",
                    "coaching.mock", "false"
            );
        }
    }

    @Inject
    AiReviewService service;

    @InjectMock
    AiJobRepository repo;

    @InjectMock
    ManagerGuidanceService managerGuidanceService;

    @BeforeEach
    void setUp() {
        reset(repo, managerGuidanceService);
    }

    /** AC-4.4 — schema-invalid throw must persist as FAILED with full telemetry. */
    @Test
    void runJobInProcess_questionSuggest_schemaInvalid_persistsFullTelemetry() {
        // Stub the suggest method to throw the same exception the production
        // code now constructs (RS-4 + RS-2 follow-up: telemetry on exception).
        when(managerGuidanceService.suggestForQuestion(any(), anyList(), anyList(), anyString()))
                .thenThrow(new SchemaInvalidException(
                        List.of("isGoodAnswer is required"),
                        "{\"suggestion\":\"ok\",\"citations\":{}}",
                        150, 40, 0, "gemini-test"));

        when(repo.getRequestJson("mg-schema-invalid")).thenReturn(validEnvelope());
        when(repo.getTaskType("mg-schema-invalid")).thenReturn("MANAGER_QUESTION_SUGGEST");

        service.runJobInProcess("mg-schema-invalid");

        verify(repo).updateProcessing("mg-schema-invalid");

        // The catch block in runJobInProcess for SchemaInvalidException calls
        // the 7-arg updateFailed(jobId, errorMessage, tokenIn, tokenOut,
        // modelUsed, promptVersion, repairRetries). Verify every argument.
        ArgumentCaptor<String> jobIdCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> inCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> outCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> modelCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> promptVerCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> retriesCap = ArgumentCaptor.forClass(Integer.class);

        verify(repo).updateFailed(jobIdCap.capture(), errCap.capture(),
                inCap.capture(), outCap.capture(),
                modelCap.capture(), promptVerCap.capture(),
                retriesCap.capture(),
                any(), any());  // CC-5: cacheHit + cachedTokens — null on this path (no cacheRequest)

        assertEquals("mg-schema-invalid", jobIdCap.getValue());
        assertTrue(errCap.getValue().startsWith("SCHEMA_INVALID"),
                "errorMessage must use the SCHEMA_INVALID tag so ops dashboards bucket correctly: "
                        + errCap.getValue());
        assertEquals(Integer.valueOf(150), inCap.getValue(),
                "tokenIn must propagate from the exception to the AiJobs row");
        assertEquals(Integer.valueOf(40), outCap.getValue());
        assertEquals("gemini-test", modelCap.getValue(),
                "modelUsed from the exception takes precedence over the configured model");
        assertEquals("QUESTION_SUGGEST_V3", promptVerCap.getValue(),
                "promptVersion comes from promptVersionFor(taskType); CC-4a follow-up "
                        + "bumped V2 → V3 to invalidate caches built with the broken body shape");
        assertEquals(Integer.valueOf(0), retriesCap.getValue(),
                "repairRetries=0 — a non-zero value would indicate the repair loop regressed");

        // Never marked COMPLETED — would mean the legacy stub fallback returned.
        verify(repo, never()).updateCompleted(anyString(), anyString(),
                any(), any(), any(), any(), any());
    }

    /** Sanity guard — the modelUsed fallback when the exception lacks one. */
    @Test
    void runJobInProcess_questionSuggest_schemaInvalid_modelFallback() {
        // Pre-RS-2 SchemaInvalidException could be constructed via the legacy
        // 2-arg ctor without modelUsed. The catch block must fall back to the
        // configured gemini.model property in that case so the row is never
        // missing model attribution entirely.
        when(managerGuidanceService.suggestForQuestion(any(), anyList(), anyList(), anyString()))
                .thenThrow(new SchemaInvalidException(
                        List.of("citations is required (use {})"),
                        "{}"));  // legacy 2-arg ctor — all telemetry null

        when(repo.getRequestJson("mg-legacy-ex")).thenReturn(validEnvelope());
        when(repo.getTaskType("mg-legacy-ex")).thenReturn("MANAGER_QUESTION_SUGGEST");

        service.runJobInProcess("mg-legacy-ex");

        ArgumentCaptor<String> modelCap = ArgumentCaptor.forClass(String.class);
        verify(repo).updateFailed(eq("mg-legacy-ex"), anyString(),
                any(), any(),
                modelCap.capture(), eq("QUESTION_SUGGEST_V3"),
                any(),
                any(), any());  // CC-5: cacheHit + cachedTokens

        assertNotNull(modelCap.getValue(),
                "modelUsed must never be null on a FAILED row — fall back to configured model");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Minimum-valid envelope for MANAGER_QUESTION_SUGGEST dispatch. */
    private static String validEnvelope() {
        return "{\"taskType\":\"MANAGER_QUESTION_SUGGEST\","
                + "\"reviewId\":\"rev-x\",\"requestedBy\":\"manager-id\","
                + "\"payload\":{"
                + "  \"questionId\":\"q1\",\"questionText\":\"Describe a recent win.\","
                + "  \"managerCurrentAnswer\":\"Shipped a thing.\","
                + "  \"discipline\":\"Software & DevOps Engineering\","
                + "  \"seniority\":\"Engineer\","
                + "  \"employeeReview\":{\"answers\":[{\"questionId\":\"q1\","
                + "    \"questionText\":\"Describe a recent win.\",\"response\":\"Delivered milestone.\"}]}"
                + "}}";
    }
}

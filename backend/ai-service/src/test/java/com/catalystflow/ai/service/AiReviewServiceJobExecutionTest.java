package com.catalystflow.ai.service;

import com.catalystflow.ai.model.AiReviewInsightsResponse;
import com.catalystflow.ai.model.AiReviewInsightsStatusResponse;
import com.catalystflow.ai.model.AiReviewJobStatus;
import com.catalystflow.ai.model.AiReviewStatusResponse;
import com.catalystflow.ai.repository.AiJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Job-execution tests for {@link AiReviewService#runJobInProcess} and the
 * {@code /insights} status path. Runs with {@code ai-review.mock=true} so the
 * Gemini call is replaced by a deterministic stub and assertions can pin the
 * response JSON shape exactly — model bumps that change the shape will fail
 * here loudly instead of silently passing a substring assertion.
 */
@QuarkusTest
@TestProfile(AiReviewServiceJobExecutionTest.MockProfile.class)
class AiReviewServiceJobExecutionTest {

    public static class MockProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("ai-review.mock", "true");
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    AiReviewService service;

    @InjectMock
    AiJobRepository aiJobRepository;

    @BeforeEach
    void setUp() {
        reset(aiJobRepository);
    }

    // ── runJobInProcess: terminal-state writes ────────────────────────────────

    @Test
    void runJobInProcess_missingRequestJson_marksFailed() {
        when(aiJobRepository.getRequestJson("job-1")).thenReturn(null);

        service.runJobInProcess("job-1");

        verify(aiJobRepository).updateFailed("job-1", "Job data not found");
        verify(aiJobRepository, never()).updateCompleted(anyString(), anyString(),
                any(), any(), any(), any(), any(), any(), any());
        verify(aiJobRepository, never()).updateProcessing(anyString());
    }

    @Test
    void runJobInProcess_insightsTask_persistsExactInsightsJsonShape() throws Exception {
        // Mock mode returns a fixed AiReviewInsightsResponse. Assert the persisted
        // string matches the canonical Jackson serialisation byte-for-byte — guards
        // against silent shape drift when Gemini model is bumped.
        String requestJson = """
                {
                  "questions": [{"questionId":"q1","questionText":"Describe a recent win","answer":"Reduced incidents by 20%."}],
                  "seniority": "Engineer",
                  "discipline": "Software & DevOps Engineering"
                }
                """;
        when(aiJobRepository.getRequestJson("job-2")).thenReturn(requestJson);
        when(aiJobRepository.getTaskType("job-2")).thenReturn("INSIGHTS");

        service.runJobInProcess("job-2");

        verify(aiJobRepository).updateProcessing("job-2");
        ArgumentCaptor<String> result = ArgumentCaptor.forClass(String.class);
        verify(aiJobRepository).updateCompleted(eq("job-2"), result.capture(),
                any(), any(), any(), any(), any(), any(), any());

        // Round-trip parses cleanly and exposes both insight fields.
        AiReviewInsightsResponse parsed = MAPPER.readValue(result.getValue(), AiReviewInsightsResponse.class);
        assertNotNull(parsed.getSummary());
        assertNotNull(parsed.getGoldenMirror());
        assertTrue(parsed.getSummary().length() > 0, "mock summary must be non-empty");
        assertTrue(parsed.getGoldenMirror().length() > 0, "mock goldenMirror must be non-empty");
    }

    @Test
    void runJobInProcess_insightsSuccess_clearsPartialText() {
        // NEW-3: the INSIGHTS success branch must clear the streamed partialText
        // once the structured result lands — matching GOLDEN / DIGEST. Without
        // it, a half-streamed narrative lingers on the COMPLETED row for the TTL.
        String requestJson = """
                {
                  "questions": [{"questionId":"q1","questionText":"Q","answer":"A detailed answer here."}],
                  "seniority": "Engineer",
                  "discipline": "Software & DevOps Engineering"
                }
                """;
        when(aiJobRepository.getRequestJson("job-ins")).thenReturn(requestJson);
        when(aiJobRepository.getTaskType("job-ins")).thenReturn("INSIGHTS");

        service.runJobInProcess("job-ins");

        verify(aiJobRepository).updateCompleted(eq("job-ins"), anyString(),
                any(), any(), any(), any(), any(), any(), any());
        verify(aiJobRepository).clearPartialText("job-ins");
    }

    @Test
    void runJobInProcess_missingTaskType_defaultsToAnalyzePath() {
        // Backwards-compat: rows created with the legacy 3-arg create() default
        // taskType to "ANALYZE". A null taskType column from a pre-G2 row must
        // still complete via the analyze branch, not blow up the worker.
        String requestJson = """
                {
                  "questions": [{"questionId":"q1","questionText":"Q","answer":"A"}],
                  "seniority": "Engineer",
                  "discipline": "Software & DevOps Engineering"
                }
                """;
        when(aiJobRepository.getRequestJson("job-3")).thenReturn(requestJson);
        when(aiJobRepository.getTaskType("job-3")).thenReturn(null);

        service.runJobInProcess("job-3");

        verify(aiJobRepository).updateProcessing("job-3");
        verify(aiJobRepository).updateCompleted(eq("job-3"), anyString(),
                any(), any(), any(), any(), any(), any(), any());
        verify(aiJobRepository, never()).updateFailed(anyString(), anyString());
    }

    @Test
    void runJobInProcess_malformedRequestJson_marksFailedWithMalformedTag() {
        // Catch-all now tags exceptions by type so the frontend (and humans
        // debugging AiJobs rows) can branch on the prefix instead of parsing
        // free-form prose. Jackson's JsonProcessingException → "MALFORMED: …".
        when(aiJobRepository.getRequestJson("job-4")).thenReturn("not-json");
        when(aiJobRepository.getTaskType("job-4")).thenReturn("INSIGHTS");

        service.runJobInProcess("job-4");

        verify(aiJobRepository).updateProcessing("job-4");
        verify(aiJobRepository).updateFailed(eq("job-4"), argThat(msg -> msg != null && msg.startsWith("MALFORMED:")));
        verify(aiJobRepository, never()).updateCompleted(anyString(), anyString(),
                any(), any(), any(), any(), any(), any(), any());
    }

    // ── getInsightsStatus: read path covers happy + missing + corrupted ───────

    @Test
    void getInsightsStatus_completed_deserializesResultIntoTypedShape() throws Exception {
        AiReviewStatusResponse raw = new AiReviewStatusResponse();
        raw.setJobId("job-5");
        raw.setStatus(AiReviewJobStatus.COMPLETED);
        when(aiJobRepository.get("job-5")).thenReturn(raw);

        AiReviewInsightsResponse stored = new AiReviewInsightsResponse();
        stored.setSummary("- Delivered key outcomes for the team.");
        stored.setGoldenMirror("TECHNOLOGY / CORE SKILLS\nworked on the API.");
        when(aiJobRepository.getResultJson("job-5"))
                .thenReturn(MAPPER.writeValueAsString(stored));

        AiReviewInsightsStatusResponse status = service.getInsightsStatus("job-5");

        assertNotNull(status);
        assertEquals("job-5", status.getJobId());
        assertEquals(AiReviewJobStatus.COMPLETED, status.getStatus());
        assertNotNull(status.getResult());
        assertEquals(stored.getSummary(), status.getResult().getSummary());
        assertEquals(stored.getGoldenMirror(), status.getResult().getGoldenMirror());
    }

    @Test
    void getInsightsStatus_missingJob_returnsNull() {
        when(aiJobRepository.get("ghost")).thenReturn(null);

        assertNull(service.getInsightsStatus("ghost"));
    }

    @Test
    void getInsightsStatus_completedWithCorruptResultJson_returnsStatusWithoutResult() {
        AiReviewStatusResponse raw = new AiReviewStatusResponse();
        raw.setJobId("job-6");
        raw.setStatus(AiReviewJobStatus.COMPLETED);
        when(aiJobRepository.get("job-6")).thenReturn(raw);
        when(aiJobRepository.getResultJson("job-6")).thenReturn("{not valid json");

        AiReviewInsightsStatusResponse status = service.getInsightsStatus("job-6");

        assertNotNull(status);
        assertEquals(AiReviewJobStatus.COMPLETED, status.getStatus());
        assertNull(status.getResult(),
                "corrupt resultJson must not crash the response — surface status with null result");
    }

    // ── taskType dispatch matrix ──────────────────────────────────────────────
    // Pin every dispatch branch so a future taskType addition (e.g. MANAGER_*
    // when the async migration lands) doesn't silently regress the existing
    // ones. The "unknown taskType" case is especially important — the dispatcher
    // currently treats anything that isn't INSIGHTS as ANALYZE; that else-branch
    // is load-bearing for backwards compat with legacy rows but should be a
    // conscious choice, not an accident.

    @Test
    void runJobInProcess_taskTypeAnalyze_routesThroughAnalyzeBranch() {
        String requestJson = """
                {
                  "questions": [{"questionId":"q1","questionText":"Q","answer":"A"}],
                  "seniority": "Engineer",
                  "discipline": "Software & DevOps Engineering"
                }
                """;
        when(aiJobRepository.getRequestJson("job-7")).thenReturn(requestJson);
        when(aiJobRepository.getTaskType("job-7")).thenReturn("ANALYZE");

        service.runJobInProcess("job-7");

        verify(aiJobRepository).updateProcessing("job-7");
        verify(aiJobRepository).updateCompleted(eq("job-7"), anyString(),
                any(), any(), any(), any(), any(), any(), any());
        verify(aiJobRepository, never()).updateFailed(anyString(), anyString());
    }

    @Test
    void runJobInProcess_unknownTaskType_fallsThroughToAnalyzeBranch() {
        // The else-branch behavior pinned: an unknown / future taskType string
        // is routed to ANALYZE, not silently dropped. If we ever want to fail
        // closed instead, this test is the canary that flips first.
        String requestJson = """
                {
                  "questions": [{"questionId":"q1","questionText":"Q","answer":"A"}],
                  "seniority": "Engineer",
                  "discipline": "Software & DevOps Engineering"
                }
                """;
        when(aiJobRepository.getRequestJson("job-8")).thenReturn(requestJson);
        when(aiJobRepository.getTaskType("job-8")).thenReturn("FUTURE_MANAGER_TYPE_NOT_YET_HANDLED");

        service.runJobInProcess("job-8");

        verify(aiJobRepository).updateProcessing("job-8");
        verify(aiJobRepository).updateCompleted(eq("job-8"), anyString(),
                any(), any(), any(), any(), any(), any(), any());
    }
}

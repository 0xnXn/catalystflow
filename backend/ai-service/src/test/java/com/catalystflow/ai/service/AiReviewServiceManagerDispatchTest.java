package com.catalystflow.ai.service;

import com.catalystflow.ai.model.AiReviewJobStatus;
import com.catalystflow.ai.model.AiReviewStatusResponse;
import com.catalystflow.ai.model.ManagerGoldenReview;
import com.catalystflow.ai.model.QuestionSuggestRequest;
import com.catalystflow.ai.model.ReviewGuidanceResponse;
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
 * Coverage for the G3 manager-flow dispatcher branches in
 * {@link AiReviewService#runJobInProcess}. Each manager taskType must:
 * <ol>
 *   <li>load the review snapshot from DDB,</li>
 *   <li>invoke the appropriate Gemini-stub (mock mode produces deterministic output),</li>
 *   <li>persist the typed result via {@code updateCompleted}.</li>
 * </ol>
 *
 * <p>Without these tests, the new dispatcher branches were undiscoverable
 * regressions waiting to happen. The {@code MockProfile} below activates
 * {@code coaching.mock=true} so {@link ManagerGuidanceService} returns its
 * mock outputs without calling real Gemini.
 */
@QuarkusTest
@TestProfile(AiReviewServiceManagerDispatchTest.MockProfile.class)
class AiReviewServiceManagerDispatchTest {

    public static class MockProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "ai-review.mock", "true",
                    "coaching.mock", "true"
            );
        }
    }

    @Inject
    AiReviewService service;

    @InjectMock
    AiJobRepository repo;

    @BeforeEach
    void setUp() {
        reset(repo);
    }

    /**
     * Build the canonical (cycleId, subject) → reviewSnapshot fixture that the
     * dispatcher will hand to {@link AiReviewService#computeManagerAnalyze}/Golden.
     * The snapshot is loaded by {@link AiReviewService#getReviewSnapshot} which
     * reads directly from DDB; we mock the row.
     */
    private static AiReviewStatusResponse rowFor(String jobId) {
        AiReviewStatusResponse row = new AiReviewStatusResponse();
        row.setJobId(jobId);
        row.setStatus(AiReviewJobStatus.PENDING);
        return row;
    }

    // Note: dispatcher tests for MANAGER_GOLDEN need a real
    // Reviews DDB table because getReviewSnapshot reads directly from
    // {@code dynamoDb}. Those branches are covered by the integration tests
    // that bootstrap a local DDB. The tests here cover the branches that DO
    // NOT need the snapshot loader: MANAGER_QUESTION_SUGGEST (uses the
    // envelope payload directly) and getJobStatus (reads the cached row).

    @Test
    void runJobInProcess_managerQuestionSuggest_missingPayload_marksFailed() {
        // Defensive: payload is set by startManagerQuestionSuggest at enqueue
        // time. A row with taskType=MANAGER_QUESTION_SUGGEST but null payload
        // is malformed; the dispatcher must mark FAILED, not silently complete.
        when(repo.getRequestJson("mg-3")).thenReturn(
                "{\"taskType\":\"MANAGER_QUESTION_SUGGEST\",\"reviewId\":\"rev-3\",\"requestedBy\":\"m\"}");
        when(repo.getTaskType("mg-3")).thenReturn("MANAGER_QUESTION_SUGGEST");

        service.runJobInProcess("mg-3");

        verify(repo).updateProcessing("mg-3");
        verify(repo).updateFailed(eq("mg-3"), anyString());
        verify(repo, never()).updateCompleted(anyString(), anyString(),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void runJobInProcess_managerQuestionSuggest_validPayload_persistsSuggestion() throws Exception {
        QuestionSuggestRequest payload = new QuestionSuggestRequest();
        payload.questionId = "q1";
        payload.questionText = "Describe a recent win.";
        payload.managerCurrentAnswer = "Shipped a thing.";
        payload.discipline = "Software & DevOps Engineering";
        payload.seniority = "Engineer";
        payload.employeeReview = new QuestionSuggestRequest.EmployeeReview();
        QuestionSuggestRequest.EmployeeAnswer ans = new QuestionSuggestRequest.EmployeeAnswer();
        ans.questionId = "q1";
        ans.questionText = "Describe a recent win.";
        ans.response = "Delivered project milestone.";
        payload.employeeReview.answers = java.util.List.of(ans);

        // Minimal envelope — payload field carries the per-question prompt.
        String requestJson = "{\"taskType\":\"MANAGER_QUESTION_SUGGEST\","
                + "\"reviewId\":\"rev-4\",\"requestedBy\":\"manager-id\","
                + "\"payload\":{\"questionId\":\"q1\",\"questionText\":\"Describe a recent win.\","
                + "\"managerCurrentAnswer\":\"Shipped a thing.\","
                + "\"discipline\":\"Software & DevOps Engineering\","
                + "\"seniority\":\"Engineer\","
                + "\"employeeReview\":{\"answers\":[{\"questionId\":\"q1\",\"questionText\":\"Describe a recent win.\",\"response\":\"Delivered project milestone.\"}]}}}";
        when(repo.getRequestJson("mg-4")).thenReturn(requestJson);
        when(repo.getTaskType("mg-4")).thenReturn("MANAGER_QUESTION_SUGGEST");

        service.runJobInProcess("mg-4");

        verify(repo).updateProcessing("mg-4");
        verify(repo).updateCompleted(eq("mg-4"), anyString(),
                any(), any(), any(), any(), any(), any(), any());
        verify(repo, never()).updateFailed(anyString(), anyString());
    }

    /**
     * Pin the existing manager-shape result through getJobStatus — this is the
     * regression guard for the bug where polling /analyze/{jobId}/status would
     * silently coerce a manager result into an empty AiReviewResponse.
     */
    @Test
    void getJobStatus_completedManagerJob_returnsLooseResultWithAllFields() throws Exception {
        AiReviewStatusResponse row = new AiReviewStatusResponse();
        row.setJobId("mg-5");
        row.setStatus(AiReviewJobStatus.COMPLETED);
        when(repo.get("mg-5")).thenReturn(row);
        when(repo.getResultJson("mg-5")).thenReturn(
                "{\"missingAreas\":[\"TECH\"],\"thinAreas\":[],\"wellCoveredAreas\":[],"
                + "\"overallGuidance\":\"Add tech examples.\"}");

        Map<String, Object> body = service.getJobStatus("mg-5");

        assertNotNull(body);
        assertTrue("COMPLETED".equals(body.get("status")));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        assertNotNull(result, "result must be present and non-null when COMPLETED");
        assertTrue(result.containsKey("overallGuidance"),
                "manager-shape fields MUST round-trip — this is the regression guard for "
                + "the silent-drop bug fixed in G3");
        assertTrue(result.containsKey("missingAreas"));
    }
}

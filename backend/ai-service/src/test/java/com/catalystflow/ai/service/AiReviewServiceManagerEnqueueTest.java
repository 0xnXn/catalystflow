package com.catalystflow.ai.service;

import com.catalystflow.ai.model.AiReviewJobResponse;
import com.catalystflow.ai.model.AiReviewJobStatus;
import com.catalystflow.ai.model.QuestionSuggestRequest;
import com.catalystflow.ai.repository.AiJobRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Replaces the legacy {@code AiReviewServiceManagerClaimTest}. With G3 the
 * deterministic-jobId pattern is gone; manager flows enqueue UUID-keyed jobs
 * via {@code startJob} just like {@code /analyze} and {@code /insights}.
 *
 * <p>These tests pin the contract: each {@code startManager*} method persists
 * a row with the right taskType, returns a fresh UUID jobId + PENDING status,
 * and never invokes Gemini synchronously.
 */
@QuarkusTest
class AiReviewServiceManagerEnqueueTest {

    @Inject
    AiReviewService service;

    @InjectMock
    AiJobRepository repo;

    @InjectMock
    ManagerGuidanceService guidance;

    @BeforeEach
    void setUp() {
        reset(repo, guidance);
    }

    @Test
    void startManagerGolden_persistsRowAndReturnsPendingJob() {
        AiReviewJobResponse job = service.startManagerGolden("rev-2", "manager-id");

        assertEquals(AiReviewJobStatus.PENDING, job.getStatus());
        verify(repo).create(eq(job.getJobId()), eq("MANAGER_GOLDEN"), anyString(), anyLong());
        verify(guidance, never()).generateGoldenReview(anyString());
    }

    @Test
    void startManagerQuestionSuggest_persistsPayloadInRequestJson() {
        QuestionSuggestRequest req = new QuestionSuggestRequest();
        req.questionId = "q1";
        req.questionText = "Describe a recent win.";
        req.managerCurrentAnswer = "Shipped a thing.";
        req.discipline = "Software & DevOps Engineering";
        req.seniority = "Engineer";
        req.employeeReview = new QuestionSuggestRequest.EmployeeReview();
        QuestionSuggestRequest.EmployeeAnswer ans = new QuestionSuggestRequest.EmployeeAnswer();
        ans.questionId = "q1";
        ans.questionText = "Describe a recent win.";
        ans.response = "Delivered project milestone.";
        req.employeeReview.answers = java.util.List.of(ans);

        AiReviewJobResponse job = service.startManagerQuestionSuggest("rev-3", req);

        assertEquals(AiReviewJobStatus.PENDING, job.getStatus());
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(repo).create(eq(job.getJobId()), eq("MANAGER_QUESTION_SUGGEST"),
                jsonCaptor.capture(), anyLong());
        String json = jsonCaptor.getValue();
        assertTrue(json.contains("\"questionId\":\"q1\""),
                "envelope must carry questionId for worker dispatch");
        assertTrue(json.contains("Describe a recent win"),
                "questionText must round-trip via the payload field so the worker can rebuild the prompt");
    }

    @Test
    void startManagerGolden_ddbWriteFailure_throws500() {
        org.mockito.Mockito.doThrow(new RuntimeException("simulated DDB outage"))
                .when(repo).create(anyString(), anyString(), anyString(), anyLong());

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> service.startManagerGolden("rev-4", "manager-id"));
        assertEquals(500, ex.getResponse().getStatus());
    }
}

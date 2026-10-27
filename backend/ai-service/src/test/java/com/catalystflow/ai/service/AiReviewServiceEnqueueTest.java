package com.catalystflow.ai.service;

import com.catalystflow.ai.model.AiReviewJobResponse;
import com.catalystflow.ai.model.AiReviewJobStatus;
import com.catalystflow.ai.model.AiReviewInsightsRequest;
import com.catalystflow.ai.model.AiReviewQuestionAnswer;
import com.catalystflow.ai.model.AiReviewRequest;
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
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the G2 step 3 API switch in {@link AiReviewService#startAnalysis}.
 *
 * <p>The legacy in-process path (synchronous Gemini call) only fires when
 * {@code ai-jobs.queue-url} is empty — kept as a local-dev / unit-test
 * convenience. Deployed environments always set the queue URL via SAM, so
 * production traffic always goes through the worker.
 *
 * <p>The {@code AiReviewServiceTest} sibling class covers the in-process path
 * indirectly via the empty-queue-url default in {@code application.properties}.
 * This class explicitly drives the enqueue path with a configured queue URL
 * stub and an injected SqsClient mock.
 */
@QuarkusTest
@TestProfile(AiReviewServiceEnqueueTest.EnqueueProfile.class)
class AiReviewServiceEnqueueTest {

    /**
     * Forces ai-jobs.queue-url to a fake URL so AiReviewService takes the SQS path.
     * Setting the @ConfigProperty field directly on the CDI proxy doesn't work —
     * Quarkus needs the override at bootstrap time.
     */
    public static class EnqueueProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("ai-jobs.queue-url", "https://sqs.example.test/queue/ai-jobs");
        }
    }

    @Inject
    AiReviewService service;

    @InjectMock
    AiJobRepository aiJobRepository;

    @InjectMock
    SqsClient sqs;

    @BeforeEach
    void setUp() {
        reset(aiJobRepository, sqs);
    }

    private static AiReviewRequest sampleRequest() {
        AiReviewRequest req = new AiReviewRequest();
        req.setSeniority("Engineer");
        req.setDiscipline("Software & DevOps Engineering");
        AiReviewQuestionAnswer qa = new AiReviewQuestionAnswer();
        qa.setQuestionId("q1");
        qa.setQuestionText("Describe a recent win.");
        qa.setAnswer("Shipped the SQS migration.");
        req.setQuestions(List.of(qa));
        return req;
    }

    private static AiReviewInsightsRequest sampleInsightsRequest() {
        AiReviewInsightsRequest req = new AiReviewInsightsRequest();
        req.setSeniority("Engineer");
        req.setDiscipline("Software & DevOps Engineering");
        AiReviewQuestionAnswer qa = new AiReviewQuestionAnswer();
        qa.setQuestionId("q1");
        qa.setQuestionText("Describe a recent win.");
        qa.setAnswer("Shipped the SQS migration.");
        req.setQuestions(List.of(qa));
        return req;
    }

    @Test
    void startAnalysis_persistsJobAndEnqueues_returnsPendingResponse() {
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("sqs-msg-1").build());

        AiReviewJobResponse response = service.startAnalysis(sampleRequest());

        assertNotNull(response.getJobId(), "jobId must be set");
        assertEquals(AiReviewJobStatus.PENDING, response.getStatus(),
                "Caller sees PENDING immediately — worker flips to COMPLETED later");

        // DDB row created exactly once
        verify(aiJobRepository).create(eq(response.getJobId()), eq("ANALYZE"), anyString(), anyLong());

        // SQS sendMessage called with the queue URL and jobId as body
        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(captor.capture());
        SendMessageRequest sent = captor.getValue();
        assertEquals("https://sqs.example.test/queue/ai-jobs", sent.queueUrl());
        assertEquals(response.getJobId(), sent.messageBody(),
                "Body is the bare jobId — DDB carries the full request payload");

        // No in-process Gemini call: updateProcessing/updateCompleted come from worker.
        verify(aiJobRepository, never()).updateProcessing(anyString());
        verify(aiJobRepository, never()).updateCompleted(anyString(), anyString(),
                any(), any(), any(), any(), any());
    }

    @Test
    void startAnalysis_sqsFailure_marksJobFailed_andThrows500() {
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(SqsException.builder().message("simulated network outage").build());

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> service.startAnalysis(sampleRequest()));

        assertEquals(500, ex.getResponse().getStatus(),
                "API surfaces 500 to the caller — frontend retries normally");

        // The job row was created, then immediately marked FAILED so the polling
        // endpoint surfaces the error rather than spinning on PENDING.
        verify(aiJobRepository).create(anyString(), eq("ANALYZE"), anyString(), anyLong());
        verify(aiJobRepository).updateFailed(anyString(), contains("Failed to enqueue"));
    }

    @Test
    void startInsights_persistsJobAndEnqueues_returnsPendingResponse() {
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("sqs-msg-2").build());

        AiReviewJobResponse response = service.startInsights(sampleInsightsRequest());

        assertNotNull(response.getJobId());
        assertEquals(AiReviewJobStatus.PENDING, response.getStatus());
        verify(aiJobRepository).create(eq(response.getJobId()), eq("INSIGHTS"), anyString(), anyLong());

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(captor.capture());
        SendMessageRequest sent = captor.getValue();
        assertEquals("https://sqs.example.test/queue/ai-jobs", sent.queueUrl());
        assertEquals(response.getJobId(), sent.messageBody());
    }

    @Test
    void startInsights_sqsFailure_marksJobFailed_andThrows500() {
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenThrow(SqsException.builder().message("simulated network outage").build());

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> service.startInsights(sampleInsightsRequest()));

        assertEquals(500, ex.getResponse().getStatus());
        verify(aiJobRepository).create(anyString(), eq("INSIGHTS"), anyString(), anyLong());
        verify(aiJobRepository).updateFailed(anyString(), contains("Failed to enqueue"));
    }
}

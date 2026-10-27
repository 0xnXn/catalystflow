package com.catalystflow.ai.service;

import com.catalystflow.ai.repository.AiJobRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(AiReviewServiceJobExecutionNoMockTest.NoMockProfile.class)
class AiReviewServiceJobExecutionNoMockTest {

    public static class NoMockProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "ai-review.mock", "false",
                    "ai-review.provider-fallback", "false"
            );
        }
    }

    @Inject
    AiReviewService aiReviewService;

    @InjectMock
    AiJobRepository aiJobRepository;

    @InjectMock
    SqsClient sqs;

    @Test
    void runJobInProcess_badInsightsRequest_marksFailedWithValidationMessage() {
        when(aiJobRepository.getRequestJson("job-4")).thenReturn("{}");
        when(aiJobRepository.getTaskType("job-4")).thenReturn("INSIGHTS");

        aiReviewService.runJobInProcess("job-4");

        verify(aiJobRepository).updateFailed("job-4", "'questions' must have at least one non-empty text answer");
    }
}

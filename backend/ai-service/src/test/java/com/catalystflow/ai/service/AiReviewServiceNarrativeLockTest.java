package com.catalystflow.ai.service;

import com.catalystflow.ai.model.AiReviewJobResponse;
import com.catalystflow.ai.model.AiReviewJobStatus;
import com.catalystflow.ai.model.ManagerGoldenReview;
import com.catalystflow.ai.repository.AiJobRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-001 / OBS-B: the AI narrative of a terminal (COMPLETED/PUBLISHED) review is
 * frozen. These tests pin that both mutation paths — regenerate
 * ({@code startManagerGolden}) and edit ({@code updateManagerGoldenResult}) — throw
 * {@link ReviewLockedException} when the underlying review is terminal, never reach
 * the persistence layer, and still proceed for non-terminal reviews (and fail open
 * if the status read errors so a first generation is never wrongly blocked).
 */
@QuarkusTest
class AiReviewServiceNarrativeLockTest {

    @Inject
    AiReviewService service;

    @InjectMock
    AiJobRepository repo;

    @InjectMock
    DynamoDbClient dynamoDb;

    @BeforeEach
    void setUp() {
        reset(repo, dynamoDb);
    }

    private void stubReviewStatus(String status) {
        GetItemResponse resp = GetItemResponse.builder()
                .item(Map.of("status", AttributeValue.builder().s(status).build()))
                .build();
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(resp);
    }

    // ── Regenerate (startManagerGolden) ───────────────────────────────────────

    @Test
    void startManagerGolden_publishedReview_throwsLockedAndDoesNotEnqueue() {
        stubReviewStatus("PUBLISHED");

        assertThrows(ReviewLockedException.class,
                () -> service.startManagerGolden("rev-pub", "mgr-1"));

        verify(repo, never()).create(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void startManagerGolden_completedReview_throwsLocked() {
        stubReviewStatus("COMPLETED");

        assertThrows(ReviewLockedException.class,
                () -> service.startManagerGolden("rev-done", "mgr-1"));

        verify(repo, never()).create(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void startManagerGolden_draftReview_proceedsAndEnqueues() {
        stubReviewStatus("DRAFT");

        AiReviewJobResponse job = service.startManagerGolden("rev-draft", "mgr-1");

        assertEquals(AiReviewJobStatus.PENDING, job.getStatus());
        verify(repo).create(eq(job.getJobId()), eq("MANAGER_GOLDEN"), anyString(), anyLong());
    }

    @Test
    void startManagerGolden_statusReadFails_failsOpenAndEnqueues() {
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenThrow(new RuntimeException("simulated DDB outage"));

        AiReviewJobResponse job = service.startManagerGolden("rev-x", "mgr-1");

        assertEquals(AiReviewJobStatus.PENDING, job.getStatus());
        verify(repo).create(anyString(), eq("MANAGER_GOLDEN"), anyString(), anyLong());
    }

    // ── Edit (updateManagerGoldenResult) ──────────────────────────────────────────────

    @Test
    void updateManagerGoldenResult_publishedReview_throwsLockedAndDoesNotPersist() {
        when(repo.getRequestJson("job-pub"))
                .thenReturn("{\"taskType\":\"MANAGER_GOLDEN\",\"reviewId\":\"rev-pub\",\"requestedBy\":\"mgr-1\"}");
        stubReviewStatus("PUBLISHED");

        assertThrows(ReviewLockedException.class,
                () -> service.updateManagerGoldenResult("job-pub", new ManagerGoldenReview()));

        verify(repo, never()).updateResultJson(anyString(), anyString());
    }
}

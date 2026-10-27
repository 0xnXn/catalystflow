package com.catalystflow.ai.service;

import com.catalystflow.ai.repository.AiJobRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link AiReviewService#getJobOwner(String)} — the ownership
 * resolver the manager-job endpoints use to scope reads/writes (NEW-1). Mocks
 * the repository so we exercise the real {@code requestedBy} parsing without DDB.
 */
@QuarkusTest
class AiReviewServiceManagerJobOwnerTest {

    @Inject
    AiReviewService aiReviewService;

    @InjectMock
    AiJobRepository aiJobRepository;

    @Test
    void getJobOwner_resolvesRequestedByFromManagerEnvelope() {
        when(aiJobRepository.getRequestJson("job-golden"))
                .thenReturn("{\"taskType\":\"MANAGER_GOLDEN\",\"reviewId\":\"rev-1\",\"requestedBy\":\"mgr-1\"}");

        assertEquals("mgr-1", aiReviewService.getJobOwner("job-golden"));
    }

    @Test
    void getJobOwner_resolvesRequestedByFromDigestEnvelope() {
        when(aiJobRepository.getRequestJson("job-digest"))
                .thenReturn("{\"taskType\":\"PEER_360_DIGEST\",\"cycleId\":\"c-1\","
                        + "\"subjectUserId\":\"emp-7\",\"requestedBy\":\"mgr-2\"}");

        assertEquals("mgr-2", aiReviewService.getJobOwner("job-digest"));
    }

    @Test
    void getJobOwner_nullWhenJobMissing() {
        when(aiJobRepository.getRequestJson("ghost")).thenReturn(null);

        assertNull(aiReviewService.getJobOwner("ghost"));
    }

    @Test
    void getJobOwner_nullWhenRequestedByAbsent() {
        // A self-review ANALYZE job carries no requestedBy — it must never be
        // treated as owned by anyone via the manager endpoint (fail closed).
        when(aiJobRepository.getRequestJson("job-analyze"))
                .thenReturn("{\"taskType\":\"ANALYZE\",\"seniority\":\"Engineer\"}");

        assertNull(aiReviewService.getJobOwner("job-analyze"));
    }

    @Test
    void getJobOwner_nullWhenEnvelopeUnparseable() {
        when(aiJobRepository.getRequestJson("job-bad")).thenReturn("not json at all");

        assertNull(aiReviewService.getJobOwner("job-bad"));
    }
}

package com.catalystflow.ai.worker;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.catalystflow.ai.model.AiReviewJobStatus;
import com.catalystflow.ai.model.AiReviewStatusResponse;
import com.catalystflow.ai.repository.AiJobRepository;
import com.catalystflow.ai.service.AiReviewService;
import com.catalystflow.ai.service.GeminiRateLimitedException;
import com.catalystflow.ai.service.GeminiTransientUpstreamException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AiWorkerHandler}. Exercises every branch of the worker's
 * idempotency + retry contract — single-message paths inherited from the original
 * BatchSize=1 design, plus multi-message batch paths added on the
 * ConcurrentAIExecution branch.
 */
@ExtendWith(MockitoExtension.class)
class AiWorkerHandlerTest {

    @Mock
    AiReviewService aiReviewService;

    @Mock
    AiJobRepository aiJobRepository;

    @Mock
    SqsClient sqs;

    @InjectMocks
    AiWorkerHandler handler;

    @BeforeEach
    void wireQueueUrlConfigField() throws Exception {
        // The handler's jobsQueueUrl is injected via @ConfigProperty at runtime
        // (Quarkus). Outside Quarkus it's null, which the rate-limit path treats
        // as "no queue URL → can't ChangeMessageVisibility". Force a value so
        // the ChangeMessageVisibility branch actually executes in tests.
        Field f = AiWorkerHandler.class.getDeclaredField("jobsQueueUrl");
        f.setAccessible(true);
        f.set(handler, Optional.of("https://sqs.test/q"));
    }

    private static SQSEvent eventWith(String jobId, String messageId) {
        return eventWith(jobId, messageId, 1);
    }

    private static SQSEvent eventWith(String jobId, String messageId, int receiveCount) {
        SQSEvent.SQSMessage msg = new SQSEvent.SQSMessage();
        msg.setBody(jobId);
        msg.setMessageId(messageId);
        msg.setReceiptHandle("rh-" + messageId);
        Map<String, String> attrs = new HashMap<>();
        attrs.put("ApproximateReceiveCount", String.valueOf(receiveCount));
        msg.setAttributes(attrs);
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(msg));
        return event;
    }

    /** Build a batched SQS event from any number of (jobId, messageId) pairs. */
    private static SQSEvent batchEventOf(String[]... jobMsgPairs) {
        List<SQSEvent.SQSMessage> records = new ArrayList<>();
        for (String[] pair : jobMsgPairs) {
            SQSEvent.SQSMessage m = new SQSEvent.SQSMessage();
            m.setBody(pair[0]);
            m.setMessageId(pair[1]);
            records.add(m);
        }
        SQSEvent event = new SQSEvent();
        event.setRecords(records);
        return event;
    }

    private static AiReviewStatusResponse statusFixture(AiReviewJobStatus status) {
        AiReviewStatusResponse r = new AiReviewStatusResponse();
        r.setStatus(status);
        return r;
    }

    @BeforeEach
    void setup() {
        // Mockito's lenient mode is fine here — different tests stub different methods.
    }

    // ── PENDING / PROCESSING — actually run the job ───────────────────────────

    @Test
    void pendingJob_claimsAndRuns_clearsBatchFailures() {
        when(aiJobRepository.get("job-1"))
                .thenReturn(statusFixture(AiReviewJobStatus.PENDING));
        when(aiJobRepository.tryClaim("job-1")).thenReturn(true);

        SQSBatchResponse response = handler.handleRequest(eventWith("job-1", "msg-1"), null);

        verify(aiJobRepository).tryClaim("job-1");
        verify(aiReviewService, times(1)).runJobInProcess("job-1");
        assertTrue(response.getBatchItemFailures().isEmpty(),
                "Successful processing must report zero batch failures");
    }

    @Test
    void pendingJob_lostClaimRace_skipsWithoutBurningGemini() {
        // Two workers receive the same message simultaneously. Both see PENDING.
        // Only one tryClaim() succeeds — the loser must NOT call runJobInProcess.
        when(aiJobRepository.get("job-1b"))
                .thenReturn(statusFixture(AiReviewJobStatus.PENDING));
        when(aiJobRepository.tryClaim("job-1b")).thenReturn(false);

        SQSBatchResponse response = handler.handleRequest(eventWith("job-1b", "msg-1b"), null);

        verify(aiJobRepository).tryClaim("job-1b");
        verify(aiReviewService, never()).runJobInProcess(anyString());
        assertTrue(response.getBatchItemFailures().isEmpty(),
                "Lost claim is normal — acknowledge and let SQS delete the dup");
    }

    @Test
    void processingJob_runsAgain_withoutClaim_dueToRetryAfterCrash() {
        // Worker that crashed mid-flight left status=PROCESSING. SQS redelivered.
        // tryClaim is bypassed (claim only applies to PENDING) — we re-run because
        // Gemini is idempotent and the row's terminal state will be written by
        // runJobInProcess (COMPLETED or FAILED).
        when(aiJobRepository.get("job-2"))
                .thenReturn(statusFixture(AiReviewJobStatus.PROCESSING));

        handler.handleRequest(eventWith("job-2", "msg-2"), null);

        verify(aiJobRepository, never()).tryClaim(anyString());
        verify(aiReviewService, times(1)).runJobInProcess("job-2");
    }

    // ── Idempotency — terminal states drop the message ────────────────────────

    @Test
    void completedJob_skipsProcessing() {
        when(aiJobRepository.get("job-3"))
                .thenReturn(statusFixture(AiReviewJobStatus.COMPLETED));

        SQSBatchResponse response = handler.handleRequest(eventWith("job-3", "msg-3"), null);

        verify(aiReviewService, never()).runJobInProcess(anyString());
        assertTrue(response.getBatchItemFailures().isEmpty(),
                "Duplicate delivery must be acknowledged so SQS deletes the message");
    }

    @Test
    void failedJob_skipsProcessing() {
        when(aiJobRepository.get("job-4"))
                .thenReturn(statusFixture(AiReviewJobStatus.FAILED));

        SQSBatchResponse response = handler.handleRequest(eventWith("job-4", "msg-4"), null);

        verify(aiReviewService, never()).runJobInProcess(anyString());
        assertTrue(response.getBatchItemFailures().isEmpty());
    }

    // ── Missing row — drop the message ────────────────────────────────────────

    @Test
    void missingJob_dropsMessage() {
        when(aiJobRepository.get("ghost")).thenReturn(null);

        SQSBatchResponse response = handler.handleRequest(eventWith("ghost", "msg-ghost"), null);

        verify(aiReviewService, never()).runJobInProcess(anyString());
        assertTrue(response.getBatchItemFailures().isEmpty(),
                "Orphan jobId — nothing to retry, ack so SQS deletes");
    }

    // ── Infrastructure failure — request redelivery via batch failure ─────────

    @Test
    void runtimeException_addsToBatchFailures_forSqsRedelivery() {
        when(aiJobRepository.get("job-5"))
                .thenReturn(statusFixture(AiReviewJobStatus.PENDING));
        when(aiJobRepository.tryClaim("job-5")).thenReturn(true);
        doThrow(new RuntimeException("simulated DDB outage"))
                .when(aiReviewService).runJobInProcess("job-5");

        SQSBatchResponse response = handler.handleRequest(eventWith("job-5", "msg-5"), null);

        assertEquals(1, response.getBatchItemFailures().size());
        assertEquals("msg-5", response.getBatchItemFailures().get(0).getItemIdentifier());
    }

    // ── Multi-message batch paths (added on ConcurrentAIExecution) ────────────

    @Test
    void batchOfTen_allPending_runsEveryJobOnce_withZeroFailures() {
        // Stub all 10 jobs as PENDING + claimable. Each must run exactly once.
        for (int i = 0; i < 10; i++) {
            String jobId = "batch-" + i;
            when(aiJobRepository.get(jobId)).thenReturn(statusFixture(AiReviewJobStatus.PENDING));
            when(aiJobRepository.tryClaim(jobId)).thenReturn(true);
        }

        String[][] pairs = Stream.iterate(0, n -> n + 1).limit(10)
                .map(i -> new String[]{"batch-" + i, "msg-" + i})
                .toArray(String[][]::new);

        SQSBatchResponse response = handler.handleRequest(batchEventOf(pairs), null);

        for (int i = 0; i < 10; i++) {
            verify(aiReviewService, times(1)).runJobInProcess("batch-" + i);
        }
        assertTrue(response.getBatchItemFailures().isEmpty());
    }

    @Test
    void batchOfTen_oneInfraFailure_reportsOnlyThatMessageId() {
        // 10 jobs PENDING; job-3 throws during runJobInProcess; the other 9 succeed.
        // The response must contain EXACTLY msg-3 in batchItemFailures.
        for (int i = 0; i < 10; i++) {
            String jobId = "batch-" + i;
            when(aiJobRepository.get(jobId)).thenReturn(statusFixture(AiReviewJobStatus.PENDING));
            when(aiJobRepository.tryClaim(jobId)).thenReturn(true);
        }
        // lenient() because we stub a SPECIFIC arg ("batch-3") but the worker
        // will also call runJobInProcess("batch-0..2"|"batch-4..9") on the other
        // threads — without lenient, Mockito's strict-stubbing would treat those
        // arg-mismatches as PotentialStubbingProblem and turn EVERY message into
        // a failure instead of just batch-3.
        lenient().doThrow(new RuntimeException("simulated DDB outage on this one"))
                .when(aiReviewService).runJobInProcess("batch-3");

        String[][] pairs = Stream.iterate(0, n -> n + 1).limit(10)
                .map(i -> new String[]{"batch-" + i, "msg-" + i})
                .toArray(String[][]::new);

        SQSBatchResponse response = handler.handleRequest(batchEventOf(pairs), null);

        // 9 successes, 1 failure
        for (int i = 0; i < 10; i++) {
            verify(aiReviewService, times(1)).runJobInProcess("batch-" + i);
        }
        List<String> failedIds = response.getBatchItemFailures().stream()
                .map(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                .toList();
        assertEquals(1, failedIds.size(),
                "Only the single failed message must be reported; the other 9 must be ack'd. Got: " + failedIds);
        assertEquals("msg-3", failedIds.get(0));
    }

    @Test
    void batchOfTen_mixedStates_eachHandledIndependently() {
        // Realistic mix: PENDING+claimable, PENDING+lost-race, PROCESSING, COMPLETED,
        // FAILED, missing-row. Every branch in processOneMessage is touched.
        when(aiJobRepository.get("ok-1")).thenReturn(statusFixture(AiReviewJobStatus.PENDING));
        when(aiJobRepository.tryClaim("ok-1")).thenReturn(true);

        when(aiJobRepository.get("ok-2")).thenReturn(statusFixture(AiReviewJobStatus.PENDING));
        when(aiJobRepository.tryClaim("ok-2")).thenReturn(true);

        when(aiJobRepository.get("lost-race")).thenReturn(statusFixture(AiReviewJobStatus.PENDING));
        when(aiJobRepository.tryClaim("lost-race")).thenReturn(false);

        when(aiJobRepository.get("crashed-mid-flight"))
                .thenReturn(statusFixture(AiReviewJobStatus.PROCESSING));

        when(aiJobRepository.get("already-done"))
                .thenReturn(statusFixture(AiReviewJobStatus.COMPLETED));

        when(aiJobRepository.get("already-failed"))
                .thenReturn(statusFixture(AiReviewJobStatus.FAILED));

        when(aiJobRepository.get("ghost-row")).thenReturn(null);

        SQSEvent event = batchEventOf(
                new String[]{"ok-1", "m-ok-1"},
                new String[]{"ok-2", "m-ok-2"},
                new String[]{"lost-race", "m-lost"},
                new String[]{"crashed-mid-flight", "m-crashed"},
                new String[]{"already-done", "m-done"},
                new String[]{"already-failed", "m-failed"},
                new String[]{"ghost-row", "m-ghost"}
        );

        SQSBatchResponse response = handler.handleRequest(event, null);

        // Three jobs ran: the two ok's and the PROCESSING one.
        verify(aiReviewService, times(1)).runJobInProcess("ok-1");
        verify(aiReviewService, times(1)).runJobInProcess("ok-2");
        verify(aiReviewService, times(1)).runJobInProcess("crashed-mid-flight");
        verify(aiReviewService, never()).runJobInProcess("lost-race");
        verify(aiReviewService, never()).runJobInProcess("already-done");
        verify(aiReviewService, never()).runJobInProcess("already-failed");
        verify(aiReviewService, never()).runJobInProcess("ghost-row");

        assertTrue(response.getBatchItemFailures().isEmpty(),
                "Every branch in this batch is benign — no SQS redelivery expected");
    }

    // ── Option A: 429 deferred-retry via SQS ChangeMessageVisibility ─────────

    @Test
    void rateLimited_firstReceive_shortensVisibilityTimeoutAndRequestsRedelivery() {
        // Receive 1 of 3 — handler must defer via ChangeMessageVisibility and
        // NOT mark FAILED on the AiJobs row.
        when(aiJobRepository.get("quota-job")).thenReturn(statusFixture(AiReviewJobStatus.PENDING));
        when(aiJobRepository.tryClaim("quota-job")).thenReturn(true);
        doThrow(new GeminiRateLimitedException(
                "RATE_LIMITED: Gemini quota exceeded after 3 in-process attempts (Retry-After=45000ms)",
                45L))
                .when(aiReviewService).runJobInProcess("quota-job");

        SQSBatchResponse response = handler.handleRequest(eventWith("quota-job", "msg-q", 1), null);

        // The message MUST be reported as a batch failure so SQS redelivers.
        assertEquals(1, response.getBatchItemFailures().size());
        assertEquals("msg-q", response.getBatchItemFailures().get(0).getItemIdentifier());

        // The job row MUST NOT be marked FAILED on a non-final receive.
        verify(aiJobRepository, never()).updateFailed(anyString(), anyString());

        // ChangeMessageVisibility MUST be called with Retry-After=45s.
        ArgumentCaptor<ChangeMessageVisibilityRequest> cap =
                ArgumentCaptor.forClass(ChangeMessageVisibilityRequest.class);
        verify(sqs).changeMessageVisibility(cap.capture());
        ChangeMessageVisibilityRequest req = cap.getValue();
        assertEquals("rh-msg-q", req.receiptHandle());
        assertEquals(45, req.visibilityTimeout());
        assertEquals("https://sqs.test/q", req.queueUrl());
    }

    @Test
    void rateLimited_zeroRetryAfter_clampsToFloor() {
        // Gemini sometimes returns 429 without a Retry-After header (parsed as 0).
        // The handler must clamp to MIN_RATE_LIMIT_VT_SEC (30s) so we don't
        // hammer the queue.
        when(aiJobRepository.get("quota-job")).thenReturn(statusFixture(AiReviewJobStatus.PENDING));
        when(aiJobRepository.tryClaim("quota-job")).thenReturn(true);
        doThrow(new GeminiRateLimitedException("RATE_LIMITED: no header", 0L))
                .when(aiReviewService).runJobInProcess("quota-job");

        handler.handleRequest(eventWith("quota-job", "msg-q", 1), null);

        ArgumentCaptor<ChangeMessageVisibilityRequest> cap =
                ArgumentCaptor.forClass(ChangeMessageVisibilityRequest.class);
        verify(sqs).changeMessageVisibility(cap.capture());
        assertEquals(30, cap.getValue().visibilityTimeout(),
                "Zero Retry-After must clamp UP to the 30s floor");
    }

    @Test
    void rateLimited_hugeRetryAfter_clampsToCeiling() {
        // A buggy Retry-After (e.g., several hours) shouldn't pin the queue
        // for that long — clamp DOWN to MAX_RATE_LIMIT_VT_SEC (300s).
        when(aiJobRepository.get("quota-job")).thenReturn(statusFixture(AiReviewJobStatus.PENDING));
        when(aiJobRepository.tryClaim("quota-job")).thenReturn(true);
        doThrow(new GeminiRateLimitedException("RATE_LIMITED: long Retry-After", 99_999L))
                .when(aiReviewService).runJobInProcess("quota-job");

        handler.handleRequest(eventWith("quota-job", "msg-q", 1), null);

        ArgumentCaptor<ChangeMessageVisibilityRequest> cap =
                ArgumentCaptor.forClass(ChangeMessageVisibilityRequest.class);
        verify(sqs).changeMessageVisibility(cap.capture());
        assertEquals(300, cap.getValue().visibilityTimeout(),
                "Excessive Retry-After must clamp DOWN to the 300s ceiling");
    }

    @Test
    void rateLimited_finalReceive_marksFailedAndDoesNotShortenVisibility() {
        // Receive 3 of 3 — sustained quota pressure. Handler must mark FAILED
        // (so UI can show fallback) and NOT call ChangeMessageVisibility
        // (let SQS DLQ the message).
        when(aiJobRepository.get("quota-job")).thenReturn(statusFixture(AiReviewJobStatus.PROCESSING));
        doThrow(new GeminiRateLimitedException("RATE_LIMITED: still throttled", 60L))
                .when(aiReviewService).runJobInProcess("quota-job");

        SQSBatchResponse response = handler.handleRequest(eventWith("quota-job", "msg-q", 3), null);

        // Batch failure reported so SQS DLQs the message.
        assertEquals(1, response.getBatchItemFailures().size());
        assertEquals("msg-q", response.getBatchItemFailures().get(0).getItemIdentifier());

        // AiJobs row MUST be marked FAILED with the RATE_LIMITED tag.
        verify(aiJobRepository).updateFailed(eq("quota-job"), contains("RATE_LIMITED"));

        // Visibility must NOT be shortened — let the message age out into DLQ.
        verify(sqs, never()).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
    }

    @Test
    void rateLimited_changeVisibilityFails_stillReportsBatchFailure() {
        // Defensive — if ChangeMessageVisibility fails (e.g., expired receipt
        // handle), the message must still be reported as a batch failure so
        // SQS redelivers on the default queue visibility timeout.
        when(aiJobRepository.get("quota-job")).thenReturn(statusFixture(AiReviewJobStatus.PENDING));
        when(aiJobRepository.tryClaim("quota-job")).thenReturn(true);
        doThrow(new GeminiRateLimitedException("RATE_LIMITED: x", 45L))
                .when(aiReviewService).runJobInProcess("quota-job");
        doThrow(software.amazon.awssdk.services.sqs.model.SqsException.builder()
                        .message("ReceiptHandleIsInvalid").build())
                .when(sqs).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));

        SQSBatchResponse response = handler.handleRequest(eventWith("quota-job", "msg-q", 1), null);

        // Still a batch failure — fallback to default queue VT.
        assertEquals(1, response.getBatchItemFailures().size());
        // Still NOT marked FAILED — only the final-receive path does that.
        verify(aiJobRepository, never()).updateFailed(anyString(), anyString());
    }

    // ── Transient upstream 5xx deferred-retry (parity with 429) ──────────────

    @Test
    void transientUpstream_firstReceive_defersViaVisibilityFloorAndDoesNotMarkFailed() {
        // 503 "model overloaded" with no Retry-After (retryAfterSeconds=0). Receive
        // 1 of 3 — handler must defer via ChangeMessageVisibility clamped to the
        // 30s floor and NOT mark the AiJobs row FAILED.
        when(aiJobRepository.get("ovl-job")).thenReturn(statusFixture(AiReviewJobStatus.PENDING));
        when(aiJobRepository.tryClaim("ovl-job")).thenReturn(true);
        doThrow(new GeminiTransientUpstreamException("AI service error (upstream 503)", 503, 0L))
                .when(aiReviewService).runJobInProcess("ovl-job");

        SQSBatchResponse response = handler.handleRequest(eventWith("ovl-job", "msg-ovl", 1), null);

        assertEquals(1, response.getBatchItemFailures().size());
        assertEquals("msg-ovl", response.getBatchItemFailures().get(0).getItemIdentifier());
        verify(aiJobRepository, never()).updateFailed(anyString(), anyString());

        ArgumentCaptor<ChangeMessageVisibilityRequest> cap =
                ArgumentCaptor.forClass(ChangeMessageVisibilityRequest.class);
        verify(sqs).changeMessageVisibility(cap.capture());
        assertEquals(30, cap.getValue().visibilityTimeout(),
                "503 has no Retry-After → clamp UP to the 30s floor");
        assertEquals("rh-msg-ovl", cap.getValue().receiptHandle());
    }

    @Test
    void transientUpstream_finalReceive_marksFailedWithUpstream5xxTag() {
        // Receive 3 of 3 — sustained 503. Handler must mark FAILED tagged
        // UPSTREAM_5XX (503) (so the polling UI stops) and NOT shorten visibility
        // (let SQS DLQ the message).
        when(aiJobRepository.get("ovl-job")).thenReturn(statusFixture(AiReviewJobStatus.PROCESSING));
        doThrow(new GeminiTransientUpstreamException("AI service error (upstream 503)", 503, 0L))
                .when(aiReviewService).runJobInProcess("ovl-job");

        SQSBatchResponse response = handler.handleRequest(eventWith("ovl-job", "msg-ovl", 3), null);

        assertEquals(1, response.getBatchItemFailures().size());
        verify(aiJobRepository).updateFailed(eq("ovl-job"), contains("UPSTREAM_5XX (503)"));
        verify(sqs, never()).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
    }

    @Test
    void transientUpstream_ioCause_finalReceive_marksFailedUnavailable() {
        // upstreamCode 0 (post-retry IO failure) → classifyError tags UNAVAILABLE.
        when(aiJobRepository.get("io-job")).thenReturn(statusFixture(AiReviewJobStatus.PROCESSING));
        doThrow(new GeminiTransientUpstreamException("AI service unavailable (IO: IOException)", 0, 0L))
                .when(aiReviewService).runJobInProcess("io-job");

        handler.handleRequest(eventWith("io-job", "msg-io", 3), null);

        verify(aiJobRepository).updateFailed(eq("io-job"), contains("UNAVAILABLE"));
    }

    // ── SPEC-503: switch to fallback model on SQS redelivery ─────────────────

    @Test
    void redelivery_secondReceive_togglesFallbackOnAroundRunThenOff() {
        // First redelivery (receiveCount=2) of a job left PROCESSING by a prior
        // transient-5xx defer. The worker MUST flip the model-fallback switch ON
        // before re-running, then OFF in a finally — so runJobInProcess resolves
        // the task's fallback model (gemini-2.5-flash) on this round.
        when(aiJobRepository.get("ovl-job")).thenReturn(statusFixture(AiReviewJobStatus.PROCESSING));

        handler.handleRequest(eventWith("ovl-job", "msg-ovl", 2), null);

        InOrder ord = inOrder(aiReviewService);
        ord.verify(aiReviewService).setModelFallback(true);
        ord.verify(aiReviewService).runJobInProcess("ovl-job");
        ord.verify(aiReviewService).setModelFallback(false);
    }

    @Test
    void firstReceive_neverTogglesFallbackOn() {
        // Receive 1 — the primary model must be used. The switch is never turned
        // ON; the job runs exactly as before this feature.
        when(aiJobRepository.get("job-1")).thenReturn(statusFixture(AiReviewJobStatus.PENDING));
        when(aiJobRepository.tryClaim("job-1")).thenReturn(true);

        handler.handleRequest(eventWith("job-1", "msg-1", 1), null);

        verify(aiReviewService, never()).setModelFallback(true);
        verify(aiReviewService, times(1)).runJobInProcess("job-1");
    }

    @Test
    void redelivery_fallbackClearedEvenWhenRunThrows() {
        // The OFF reset lives in a finally — a transient-upstream throw on the
        // redelivery must still clear the switch so the pooled thread can't leak
        // fallback state into the next message it handles.
        when(aiJobRepository.get("ovl-job")).thenReturn(statusFixture(AiReviewJobStatus.PROCESSING));
        doThrow(new GeminiTransientUpstreamException("AI service error (upstream 503)", 503, 0L))
                .when(aiReviewService).runJobInProcess("ovl-job");

        handler.handleRequest(eventWith("ovl-job", "msg-ovl", 2), null);

        InOrder ord = inOrder(aiReviewService);
        ord.verify(aiReviewService).setModelFallback(true);
        ord.verify(aiReviewService).runJobInProcess("ovl-job");
        ord.verify(aiReviewService).setModelFallback(false);
    }

    // ── End Option A ─────────────────────────────────────────────────────────

    @Test
    void batchOfTen_runsTrulyConcurrently_notSerially() {
        // Latch proves the fan-out is parallel: every thread waits for ALL 10 to arrive
        // before any may proceed. If the handler ran serially this test would deadlock
        // (the first call would block waiting for 9 more arrivals that never come).
        int batch = 10;
        CountDownLatch arrived = new CountDownLatch(batch);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger inFlight = new AtomicInteger(0);

        for (int i = 0; i < batch; i++) {
            String jobId = "para-" + i;
            when(aiJobRepository.get(jobId)).thenReturn(statusFixture(AiReviewJobStatus.PENDING));
            when(aiJobRepository.tryClaim(jobId)).thenReturn(true);
        }

        // Each runJobInProcess call announces itself, observes the high-water mark,
        // and waits for ALL 10 callers to arrive before returning. Serial execution
        // can never satisfy the latch — the test would time out.
        doAnswer(invocation -> {
            inFlight.incrementAndGet();
            maxConcurrent.updateAndGet(prev -> Math.max(prev, inFlight.get()));
            arrived.countDown();
            assertTrue(arrived.await(5, TimeUnit.SECONDS),
                    "All " + batch + " messages must reach the worker concurrently");
            inFlight.decrementAndGet();
            return null;
        }).when(aiReviewService).runJobInProcess(anyString());

        String[][] pairs = Stream.iterate(0, n -> n + 1).limit(batch)
                .map(i -> new String[]{"para-" + i, "m-para-" + i})
                .toArray(String[][]::new);

        SQSBatchResponse response = handler.handleRequest(batchEventOf(pairs), null);

        assertNotNull(response);
        assertTrue(response.getBatchItemFailures().isEmpty());
        assertEquals(batch, maxConcurrent.get(),
                "All 10 jobs must run in parallel; observed peak concurrency of " + maxConcurrent.get());
        assertFalse(arrived.getCount() != 0,
                "Every job must have arrived at the worker (latch fully counted down)");
    }
}

package com.catalystflow.ai.worker;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.catalystflow.ai.model.AiReviewJobStatus;
import com.catalystflow.ai.model.AiReviewStatusResponse;
import com.catalystflow.ai.repository.AiJobRepository;
import com.catalystflow.ai.service.AiReviewService;
import com.catalystflow.ai.service.GeminiRateLimitedException;
import com.catalystflow.ai.service.GeminiTransientUpstreamException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SQS-driven worker for AI review jobs (Epic 5 G2).
 *
 * <p>Each SQS message body is a {@code jobId} string. For every message in the
 * batch the handler:
 * <ol>
 *   <li>Looks up the job in DynamoDB. Drops the message if the row is missing
 *       (admin-deleted, expired, or never created — nothing to recover).</li>
 *   <li>Skips terminal states ({@code COMPLETED} / {@code FAILED}) — SQS may
 *       redeliver after visibility timeout, so idempotency is mandatory.</li>
 *   <li>Otherwise delegates to {@link AiReviewService#runJobInProcess(String)}
 *       which runs Gemini and writes the result/error back to DynamoDB.</li>
 * </ol>
 *
 * <p>Failures inside {@code runJobInProcess} are already terminal (caught and
 * stored as {@code FAILED}) — those are NOT added to {@code batchItemFailures}
 * because retrying won't help. Only true infrastructure exceptions (DDB outage,
 * OOM) bubble up here and trigger SQS redelivery via visibility timeout.
 *
 * <p><b>2026-05-20 (ConcurrentAIExecution):</b> messages within a batch are
 * processed in parallel via a fixed thread pool sized to the SQS BatchSize
 * (currently 10, see template.yaml). Per-message isolation is preserved — each
 * message has its own try/catch, its own claim attempt, and reports its own
 * messageId on infrastructure failure.
 *
 * <p><b>2026-05-20 (ConcurrentAIExecution, Option A):</b> 429 quota exhaustion
 * is now a deferred retry via SQS instead of an immediate FAILED row. When
 * {@code AiReviewService.callGemini} exhausts its in-process 429 retry budget
 * it throws {@link GeminiRateLimitedException}; this handler catches it, shortens
 * the message's SQS visibility timeout to the Gemini {@code Retry-After} value
 * (floored/ceilinged sensibly), and reports the message as a batch failure so
 * SQS redelivers it on a fresh quota window. After
 * {@link #MAX_RECEIVES_BEFORE_DLQ} receives the worker writes FAILED on the
 * AiJobs row and lets SQS DLQ the message — at that point the quota pressure
 * is sustained, not transient.
 */
@Named("aiWorker")
@ApplicationScoped
public class AiWorkerHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private static final Logger LOG = Logger.getLogger(AiWorkerHandler.class);

    /**
     * Caps the per-invocation parallelism at the SQS BatchSize. Held statically
     * so the pool survives Lambda freeze/thaw and is reused across invocations
     * of the same container — creating a new pool per invocation would defeat
     * the cold-start amortisation benefit of larger batches.
     */
    private static final int MAX_BATCH_SIZE = 10;
    private static final ExecutorService BATCH_EXECUTOR =
            Executors.newFixedThreadPool(MAX_BATCH_SIZE, r -> {
                Thread t = new Thread(r, "ai-worker-batch");
                t.setDaemon(true);
                return t;
            });

    /**
     * Must match {@code RedrivePolicy.maxReceiveCount} in template.yaml. SQS
     * doesn't expose this value to consumers, so we keep it in sync manually.
     * On the Nth receive of a rate-limited message, we stop deferring and
     * mark the AiJobs row FAILED so the UI can render a fallback even though
     * SQS will also DLQ the message right after we return the batch failure.
     */
    private static final int MAX_RECEIVES_BEFORE_DLQ = 3;

    /** Floor for the shortened SQS visibility timeout (seconds). */
    private static final int MIN_RATE_LIMIT_VT_SEC = 30;
    /** Ceiling — keeps any one job from sleeping more than 5 min on the queue. */
    private static final int MAX_RATE_LIMIT_VT_SEC = 300;

    @Inject
    AiReviewService aiReviewService;

    @Inject
    AiJobRepository aiJobRepository;

    @Inject
    SqsClient sqs;

    /**
     * Same config name used by AiReviewService for the producer side. Optional
     * because the local dev profile may omit it (and we want a clear log line
     * rather than a startup-time crash); in that mode rate-limit deferral
     * silently falls back to "let SQS retry on default visibility timeout".
     */
    @ConfigProperty(name = "ai-jobs.queue-url")
    Optional<String> jobsQueueUrl;

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context ctx) {
        // Fan out: each message runs on its own thread.
        List<CompletableFuture<Optional<String>>> futures = event.getRecords().stream()
                .map(msg -> CompletableFuture.supplyAsync(() -> processOneMessage(msg), BATCH_EXECUTOR))
                .toList();

        // Gather: wait for all messages to finish, collect only the messageIds
        // that need SQS redelivery. join() throws on truly unexpected errors —
        // processOneMessage catches RuntimeException and returns its own messageId,
        // so the only way join() throws here is on Errors (OOM etc.), which we
        // intentionally don't catch.
        List<SQSBatchResponse.BatchItemFailure> failures = futures.stream()
                .map(CompletableFuture::join)
                .flatMap(Optional::stream)
                .map(messageId -> SQSBatchResponse.BatchItemFailure.builder()
                        .withItemIdentifier(messageId)
                        .build())
                .toList();

        return SQSBatchResponse.builder()
                .withBatchItemFailures(failures)
                .build();
    }

    /**
     * Process one SQS message in isolation.
     *
     * @return {@code Optional.empty()} when the message has been handled (success
     *         OR terminal-state skip OR orphan drop OR lost-claim skip); SQS
     *         deletes these. {@code Optional.of(messageId)} when the message
     *         should be redelivered — either an infrastructure failure (default
     *         visibility timeout) or a deferred rate-limit retry (shortened
     *         visibility timeout via ChangeMessageVisibility).
     */
    private Optional<String> processOneMessage(SQSEvent.SQSMessage msg) {
        String jobId = msg.getBody();

        try {
            AiReviewStatusResponse current = aiJobRepository.get(jobId);

            if (current == null) {
                // Row gone — nothing to retry. Acknowledge so SQS deletes the message.
                LOG.warnf("AI job %s not found in DDB; dropping message %s",
                        jobId, msg.getMessageId());
                return Optional.empty();
            }

            AiReviewJobStatus status = current.getStatus();
            if (status == AiReviewJobStatus.COMPLETED || status == AiReviewJobStatus.FAILED) {
                // SQS at-least-once delivery — already done. Acknowledge.
                LOG.infof("AI job %s already %s; skipping duplicate delivery", jobId, status);
                return Optional.empty();
            }

            // Step 4 idempotency: atomic PENDING → PROCESSING claim. Two workers
            // racing on the same message will see exactly one win the conditional
            // update; the loser drops the message without burning Gemini quota.
            // PROCESSING jobs (e.g. from a worker that crashed mid-flight OR
            // from a previous 429-deferred attempt) skip the claim — Gemini is
            // idempotent and the row's terminal state will be set by runJobInProcess.
            if (status == AiReviewJobStatus.PENDING && !aiJobRepository.tryClaim(jobId)) {
                return Optional.empty();
            }

            // SPEC-503: on an SQS redelivery (ApproximateReceiveCount >= 2) re-run
            // the job on the task's fallback Gemini model (uniformly
            // gemini-2.5-flash) to escape a sustained overload on the primary.
            // The switch is thread-scoped on the shared ModelResolver; clear it in
            // a finally so this pooled worker thread can't leak fallback state into
            // the next message it handles. Cause-agnostic by design (see
            // spec-503-model-fallback): a 429-deferred job would also switch, but
            // only ever to the same vetted gemini-2.5-flash.
            aiReviewService.setModelFallback(parseReceiveCount(msg) >= 2);
            try {
                aiReviewService.runJobInProcess(jobId);
            } finally {
                aiReviewService.setModelFallback(false);
            }
            return Optional.empty();

        } catch (GeminiRateLimitedException e) {
            return handleRateLimited(msg, jobId, e);
        } catch (GeminiTransientUpstreamException e) {
            // Transient upstream 5xx (e.g. 503 "model overloaded") or post-retry IO.
            // Same deferred-retry treatment as 429. MUST precede the generic
            // RuntimeException catch below (this IS-A RuntimeException) so the
            // message is deferred with a short visibility timeout + bounded by
            // MAX_RECEIVES_BEFORE_DLQ, rather than redelivered on the long default VT.
            return handleTransientUpstream(msg, jobId, e);
        } catch (RuntimeException e) {
            // Infrastructure failure (DDB outage, OOM, unexpected NPE) — let SQS redeliver
            // on the default queue visibility timeout (currently 810s).
            LOG.errorf(e, "Worker exception for job %s; SQS will redeliver", jobId);
            return Optional.of(msg.getMessageId());
        }
    }

    /**
     * Deferred-retry path for Gemini 429 rate-limit exhaustion. Two branches:
     * <ul>
     *   <li>Receives so far {@code <} {@link #MAX_RECEIVES_BEFORE_DLQ}: shorten
     *       the SQS visibility timeout to Gemini's Retry-After (clamped to
     *       {@link #MIN_RATE_LIMIT_VT_SEC}..{@link #MAX_RATE_LIMIT_VT_SEC}) and
     *       report a batch failure. SQS redelivers in ~Retry-After seconds, on
     *       a (hopefully) fresh quota window. The AiJobs row stays in PROCESSING.</li>
     *   <li>Receives so far {@code >=} {@link #MAX_RECEIVES_BEFORE_DLQ}: this is
     *       the last allowable attempt. Mark the AiJobs row FAILED with the
     *       RATE_LIMITED tag so the UI can render the fallback, then report a
     *       batch failure so SQS moves the message to the DLQ (alarm fires).</li>
     * </ul>
     */
    private Optional<String> handleRateLimited(SQSEvent.SQSMessage msg, String jobId,
                                               GeminiRateLimitedException e) {
        String failedMessage = "RATE_LIMITED: Gemini quota exceeded after " + MAX_RECEIVES_BEFORE_DLQ
                + " SQS receives × " + AiReviewService.MAX_429_RETRIES
                + " in-process retries; " + e.getMessage();
        return deferOrFail(msg, jobId, e.retryAfterSeconds, "rate-limited", failedMessage);
    }

    /**
     * Deferred-retry path for transient upstream failures (5xx 500/502/503 such as
     * 503 "model overloaded", or post-retry IO). Identical lifecycle to
     * {@link #handleRateLimited}: shorten SQS visibility and redeliver until
     * {@link #MAX_RECEIVES_BEFORE_DLQ}, then mark FAILED and DLQ. 503 carries no
     * Retry-After, so {@code retryAfterSeconds} is usually 0 and the
     * {@link #MIN_RATE_LIMIT_VT_SEC} floor (30 s) applies. The FAILED tag reuses
     * {@link AiReviewService#classifyError} so the frontend sees the same
     * {@code UPSTREAM_5XX (<code>)} / {@code UNAVAILABLE} prefix it already handles.
     */
    private Optional<String> handleTransientUpstream(SQSEvent.SQSMessage msg, String jobId,
                                                     GeminiTransientUpstreamException e) {
        String failedMessage = AiReviewService.classifyError(e)
                + "; exhausted " + MAX_RECEIVES_BEFORE_DLQ + " SQS receives";
        return deferOrFail(msg, jobId, e.retryAfterSeconds, "transient upstream", failedMessage);
    }

    /**
     * Shared deferred-retry / DLQ lifecycle for retryable upstream conditions
     * (429 quota and transient 5xx). Below {@link #MAX_RECEIVES_BEFORE_DLQ}:
     * shorten the SQS message visibility to {@code retryAfterSeconds} (clamped to
     * {@link #MIN_RATE_LIMIT_VT_SEC}..{@link #MAX_RATE_LIMIT_VT_SEC}) and report a
     * batch failure so SQS redelivers. At/after the cap: write the AiJobs row
     * FAILED with {@code failedMessage} (so the polling frontend stops) and report
     * a batch failure so SQS moves the message to the DLQ (alarm fires).
     *
     * @param logTag short cause label for log lines ("rate-limited" / "transient upstream").
     */
    private Optional<String> deferOrFail(SQSEvent.SQSMessage msg, String jobId, long retryAfterSeconds,
                                         String logTag, String failedMessage) {
        int receiveCount = parseReceiveCount(msg);

        if (receiveCount >= MAX_RECEIVES_BEFORE_DLQ) {
            // Sustained pressure — give up the deferred retry, mark FAILED so the
            // polling frontend stops spinning and shows the fallback.
            LOG.errorf("AI job %s %s on final receive (%d/%d) — marking FAILED + DLQ",
                    jobId, logTag, receiveCount, MAX_RECEIVES_BEFORE_DLQ);
            try {
                aiJobRepository.updateFailed(jobId, failedMessage);
            } catch (RuntimeException dbErr) {
                // If we can't even write FAILED, log loudly — the DLQ + AiJobs row
                // will diverge. Operator can re-sync by reading the DLQ message body.
                LOG.errorf(dbErr, "Failed to write FAILED to AiJobs for job %s after DLQ-bound", jobId);
            }
            return Optional.of(msg.getMessageId());
        }

        long visibilitySec = clamp(retryAfterSeconds, MIN_RATE_LIMIT_VT_SEC, MAX_RATE_LIMIT_VT_SEC);
        if (jobsQueueUrl.isPresent()) {
            try {
                sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                        .queueUrl(jobsQueueUrl.get())
                        .receiptHandle(msg.getReceiptHandle())
                        .visibilityTimeout((int) visibilitySec)
                        .build());
                LOG.warnf("AI job %s %s (receive %d/%d) — deferred via SQS for %ds",
                        jobId, logTag, receiveCount, MAX_RECEIVES_BEFORE_DLQ, visibilitySec);
            } catch (SqsException sqsErr) {
                // ChangeMessageVisibility can fail if the message receipt handle
                // expired (unlikely on first try, possible on a slow Lambda).
                // Fall back to default queue visibility timeout — slower, but
                // still gets a retry.
                LOG.warnf(sqsErr, "ChangeMessageVisibility failed for job %s — falling back to default VT",
                        jobId);
            }
        } else {
            // Dev-mode fallback — no queue URL configured. Let SQS handle the
            // retry on default visibility timeout (slower, but functionally fine).
            LOG.warnf("AI job %s %s but ai-jobs.queue-url not configured — using default VT",
                    jobId, logTag);
        }
        return Optional.of(msg.getMessageId());
    }

    /**
     * SQS standard messages expose receive count as a string attribute. Parses
     * defensively — any failure assumes receive count of 1 (worst case is one
     * extra retry attempt, not data loss).
     */
    private static int parseReceiveCount(SQSEvent.SQSMessage msg) {
        Map<String, String> attrs = msg.getAttributes();
        if (attrs == null) return 1;
        String raw = attrs.get("ApproximateReceiveCount");
        if (raw == null) return 1;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}

package com.catalystflow.ai.service;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Thrown by {@code callGemini} when the in-process 429 retry budget
 * ({@code MAX_429_RETRIES}) is exhausted and the caller should defer the
 * work via SQS redelivery rather than mark the AiJobs row FAILED.
 *
 * <p>Carries {@link #retryAfterSeconds} so {@code AiWorkerHandler} can
 * shorten the SQS message's visibility timeout to roughly the Gemini
 * Retry-After interval (clamped to a sensible floor/ceiling) before
 * surfacing the message back to SQS as a batch failure.
 *
 * <p>Distinct from {@link GeminiTelemetryException} — telemetry exceptions
 * are terminal (truncated / blocked / malformed; no retry will help), while
 * rate-limit exceptions are explicitly retryable on a fresh quota window.
 *
 * <p>Extends {@link RuntimeException} so it propagates from {@code callGemini}
 * through {@code runJobInProcess} to {@code AiWorkerHandler.processOneMessage}
 * without forcing a checked-throws clause on every intermediate signature.
 * The intermediate catch in {@code runJobInProcess} re-throws explicitly to
 * sidestep the generic {@code catch (Exception)} that would otherwise write
 * FAILED to the AiJobs row.
 */
@RegisterForReflection
public class GeminiRateLimitedException extends RuntimeException {

    /**
     * Retry-After value parsed from Gemini's 429 response, expressed in
     * seconds. Zero means "no header / unparseable" — caller should apply
     * its own default (a small floor like 30s is sensible). Always non-negative.
     */
    public final long retryAfterSeconds;

    public GeminiRateLimitedException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(0L, retryAfterSeconds);
    }
}

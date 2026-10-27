package com.catalystflow.ai.service;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Thrown by {@code GeminiClient} when an upstream Gemini call fails with a
 * <em>transient</em> server-side condition that survived the one in-process
 * retry, and the caller should defer the work via SQS redelivery rather than
 * mark the AiJobs row FAILED.
 *
 * <p>Transient = upstream HTTP 500 / 502 / 503 (notably 503 {@code UNAVAILABLE}
 * "the model is overloaded… usually temporary"), or a post-retry
 * {@link java.io.IOException} reaching Gemini. These are expected to clear on a
 * later attempt, exactly like a 429 quota window.
 *
 * <p>Distinct from:
 * <ul>
 *   <li>{@link GeminiRateLimitedException} — the 429 quota path. Same
 *       defer-via-SQS treatment, but a different cause/tag.</li>
 *   <li>{@link GeminiTelemetryException} — terminal (truncated / blocked /
 *       no-candidates); no retry will help.</li>
 *   <li>Non-transient upstream codes (4xx, 501, 505, …) and 504 (TIMEOUT),
 *       which {@code GeminiClient} still surfaces as a terminal
 *       {@code WebApplicationException}.</li>
 * </ul>
 *
 * <p>Carries {@link #upstreamCode} so the worker's final-attempt FAILED message
 * (after {@code MAX_RECEIVES_BEFORE_DLQ}) can report the real Gemini status as
 * {@code UPSTREAM_5XX (<code>)}, and {@link #retryAfterSeconds} so the worker can
 * shorten the SQS visibility timeout. 503s carry no {@code Retry-After}, so this
 * is usually 0 and the worker applies its own floor.
 *
 * <p>Extends {@link RuntimeException} so it propagates from the Gemini call
 * through {@code AiReviewService.runJobInProcess} to
 * {@code AiWorkerHandler.processOneMessage} without a checked-throws clause. The
 * intermediate catch in {@code runJobInProcess} re-throws it explicitly to
 * sidestep the generic {@code catch (Exception)} that would otherwise write
 * FAILED to the AiJobs row — mirroring the {@link GeminiRateLimitedException}
 * handling.
 */
@RegisterForReflection
public class GeminiTransientUpstreamException extends RuntimeException {

    /** The real upstream HTTP status (e.g. 503). 0 when the cause was an IOException. */
    public final int upstreamCode;

    /**
     * Retry-After value in seconds if Gemini supplied one, else 0 — caller
     * applies its own floor (a small value like 30s is sensible). Non-negative.
     */
    public final long retryAfterSeconds;

    public GeminiTransientUpstreamException(String message, int upstreamCode, long retryAfterSeconds) {
        super(message);
        this.upstreamCode = upstreamCode;
        this.retryAfterSeconds = Math.max(0L, retryAfterSeconds);
    }
}

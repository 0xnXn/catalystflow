package com.catalystflow.ai.service;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Thrown when a Gemini call produces a usable {@code usageMetadata} block but
 * the response itself is unusable (truncated, blocked, malformed). Carries the
 * captured token counts so the worker can persist them on the FAILED job row —
 * a truncated job still cost real money and we need that visible in AiJobs for
 * cost attribution.
 *
 * <p>Extends {@link WebApplicationException} so existing {@code classifyError}
 * logic continues to work without modification: the status code + message
 * preserve the original error class so the {@code TRUNCATED:} / {@code BLOCKED:}
 * / {@code MALFORMED:} prefix appears in {@code errorMessage} unchanged.
 */
@RegisterForReflection
public class GeminiTelemetryException extends WebApplicationException {

    public final Integer tokenIn;
    public final Integer tokenOut;
    public final String modelUsed;

    /**
     * CC-5 (AC-11.1): cache attribution for the failed Gemini call. When the
     * call used a cached prefix and Gemini then truncated/blocked, we still
     * paid for the cached tokens; persisting these onto the FAILED AiJobs row
     * keeps cost attribution complete. Nullable — null for non-cached calls
     * and for legacy constructions.
     */
    public final Boolean cacheHit;
    public final Integer cachedTokens;

    /**
     * Legacy 5-arg constructor — kept so existing callers compile. Cache
     * attribution defaults to null. Prefer the 7-arg form in any new code that
     * has the GeminiResult/GeminiClient cache fields in scope.
     */
    public GeminiTelemetryException(String message, Response.Status status,
                                    Integer tokenIn, Integer tokenOut, String modelUsed) {
        this(message, status, tokenIn, tokenOut, modelUsed, null, null);
    }

    /**
     * CC-5 follow-up: full constructor carrying cache attribution alongside
     * the existing token telemetry. Used by {@link GeminiClient#parseResponse}
     * on every throw path so the FAILED AiJobs row knows whether the failed
     * call was cached.
     */
    public GeminiTelemetryException(String message, Response.Status status,
                                    Integer tokenIn, Integer tokenOut, String modelUsed,
                                    Boolean cacheHit, Integer cachedTokens) {
        super(message, status);
        this.tokenIn = tokenIn;
        this.tokenOut = tokenOut;
        this.modelUsed = modelUsed;
        this.cacheHit = cacheHit;
        this.cachedTokens = cachedTokens;
    }
}

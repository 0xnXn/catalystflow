package com.catalystflow.ai.service;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Immutable result returned by {@link GeminiClient#call(GeminiRequest)}.
 *
 * <p>Replaces the two pre-existing duplicates:
 * <ul>
 *   <li>{@code AiReviewService.GeminiCallResult} (nested record)
 *   <li>{@code ManagerGuidanceService.GeminiResult} (nested class)
 * </ul>
 *
 * <p>{@code cacheHit} reflects whether the request body used
 * {@code cachedContent} (CC-3). True iff a {@link CacheRequest} was attached
 * to the {@link GeminiRequest} AND {@code GeminiCacheService.resolveOrCreate}
 * returned a non-blank cache name. When the cache layer degrades
 * (resolveOrCreate returns null, or returns a blank name we can't actually
 * use), the request falls through to the uncached path and {@code cacheHit}
 * is false — single source of truth aligned with the body shape.
 *
 * @param text          Concatenated non-thought parts from {@code candidates[0].content.parts}.
 * @param tokenIn       {@code usageMetadata.promptTokenCount}. Nullable when Gemini omitted usage.
 * @param tokenOut      {@code usageMetadata.candidatesTokenCount}. Nullable when Gemini omitted usage.
 * @param finishReason  Raw value from {@code candidates[0].finishReason}; "STOP" when empty in response.
 * @param modelUsed     The Gemini model identifier the call actually targeted.
 * @param cacheHit      True iff the request body used a Gemini context cache.
 * @param cachedTokens  {@code usageMetadata.cachedContentTokenCount} from
 *                      the generateContent response — the portion of
 *                      {@code tokenIn} that was served from the cached
 *                      prefix and therefore billed at the cached-storage
 *                      rate. Nullable: Gemini only emits this field on
 *                      cached calls. Persisted on {@code AiJobs} for CC-5's
 *                      cost-attribution dashboard.
 */
@RegisterForReflection
public record GeminiResult(
        String text,
        Integer tokenIn,
        Integer tokenOut,
        String finishReason,
        String modelUsed,
        boolean cacheHit,
        Integer cachedTokens) {
}

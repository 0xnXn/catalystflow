package com.catalystflow.ai.service;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Request to {@link GeminiCacheService#resolveOrCreate(CacheRequest)}.
 *
 * <p>Carries everything needed both to compose the {@code cacheKey} and to
 * issue a {@code POST /v1beta/cachedContents} call on miss. The cacheKey
 * shape (per plan AC-8.3) is:
 *
 * <pre>
 *   taskType + "#" + promptVersion + "#" + sha256_16(systemPrompt + extraPrefix)
 *            + "#" + nz(discipline) + "#" + nz(seniority) + "#" + model
 * </pre>
 *
 * <p>{@code model} is the final segment (normalized to strip a leading
 * {@code models/}) so a {@code cachedContents/<id>} created under one model is
 * never reused for a request on another — Gemini rejects that mismatch with a
 * 400, and each model legitimately needs its own cached prefix.
 *
 * <p>{@code discipline} and {@code seniority} are nullable because some
 * task types (MANAGER_GOLDEN, PEER_360_DIGEST) don't take them at the
 * prompt layer. Including them in the key means the same system prompt
 * with a different {@code (discipline, seniority)} criteria block will
 * produce a different cache row — which is correct, since the cacheable
 * prefix is {@code systemPrompt + extraPrefix} and {@code extraPrefix}
 * already encodes those criteria.
 *
 * <p>{@code ttlSeconds} tracks Gemini's own TTL. Initial values per plan §D5:
 * 3600 (ANALYZE, QUESTION_SUGGEST — high reuse), 1800 (INSIGHTS), 600
 * (MANAGER_GOLDEN, PEER_360_DIGEST — one-shot per job). Hard ceiling 3600.
 *
 * @param taskType       Task identifier (ANALYZE, INSIGHTS, MANAGER_GOLDEN, ...).
 * @param promptVersion  e.g. "ANALYZE_V2" — bumps invalidate the cache.
 * @param discipline     Performance criteria discipline; null when unused.
 * @param seniority      Performance criteria seniority; null when unused.
 * @param systemPrompt   The system instruction text.
 * @param extraPrefix    Additional cacheable prefix prepended to {@code systemPrompt}
 *                       (e.g. criteria block + style references). Null when none.
 * @param model          Gemini model identifier (e.g. "gemini-2.5-flash"). The
 *                       cachedContents API requires the "models/" prefix; the
 *                       caller may pass either form, and {@link GeminiClient}
 *                       normalises before sending.
 * @param ttlSeconds     Gemini cache TTL in seconds (also used to compute the
 *                       DDB row's {@code expiresAt}).
 */
@RegisterForReflection
public record CacheRequest(
        String taskType,
        String promptVersion,
        String discipline,
        String seniority,
        String systemPrompt,
        String extraPrefix,
        String model,
        long ttlSeconds) {
}

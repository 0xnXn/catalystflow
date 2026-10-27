package com.catalystflow.ai.service;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * Immutable request to {@link GeminiClient#call(GeminiRequest)}.
 *
 * <p>{@link #responseSchema} (RS-2..RS-6) and {@link #cacheRequest} (CC-3)
 * are nullable. When both are null the {@link GeminiClient} sends a plain
 * uncached generateContent request (no schema, full system_instruction).
 *
 * <p>Use {@link #of} for the common case, then layer in features via the
 * {@code with*} builders.
 *
 * <h2>Two interpretations of {@code userPrompt}</h2>
 * <ul>
 *   <li><b>{@code cacheRequest == null}</b> (plain uncached): {@code userPrompt}
 *       IS the full user message. {@code uncachedPrompt} is ignored.</li>
 *   <li><b>{@code cacheRequest != null}</b> (cacheable): {@code userPrompt} is
 *       the DYNAMIC-only portion. On cache hit Gemini prepends the cached
 *       prefix automatically. On cache miss (cache layer degraded) the client
 *       sends {@code uncachedPrompt} instead — the caller's pre-rendered FULL
 *       prompt that would have been sent if caching were off. This makes the
 *       byte order on cache miss a caller decision rather than a hard-coded
 *       reconstruction inside {@code GeminiClient} (CC-4a MEDIUM #3 follow-up).
 *       For this reason {@link #withCacheRequest} REQUIRES both arguments and
 *       throws when {@code uncachedPrompt} is null.</li>
 * </ul>
 *
 * @param taskName         Used in log lines and (post-RS-1) the budget switch.
 * @param systemPrompt     System-level instructions to Gemini.
 * @param userPrompt       User-turn content; dynamic-only when cacheRequest is set,
 *                         else the full user message.
 * @param maxOutputTokens  Hard cap on response tokens (visible output, not thinking).
 * @param thinkingBudget   Internal-reasoning token cap (Gemini 2.5 series).
 * @param responseSchema   Wire field name on the Gemini API request body is
 *                         {@code generationConfig.response_json_schema} (full
 *                         JSON Schema 2020-12, RJS-1 2026-05-25); the Java
 *                         record component is named {@code responseSchema} for
 *                         backward compat with the pre-RJS-1 API. RS-2..RS-6
 *                         consumers. Nullable.
 * @param cacheRequest     Cacheable-prefix descriptor. When non-null,
 *                         {@link GeminiClient} consults
 *                         {@code GeminiCacheService.resolveOrCreate}; on a
 *                         hit/create it sends {@code cachedContent: <name>}
 *                         and omits {@code system_instruction}. On null
 *                         resolve (cache layer degraded) the request falls
 *                         through to the uncached path (AC-9.3) so cache
 *                         failures are invisible to the user. Nullable.
 * @param uncachedPrompt   Full pre-rendered user message for the cache-miss
 *                         fallback path. ONLY consulted when
 *                         {@code cacheRequest != null} AND the cache layer
 *                         returned null. Caller-owned byte order — no
 *                         reconstruction inside the client. Must be non-null
 *                         whenever {@code cacheRequest != null}. Ignored
 *                         (may be null) when {@code cacheRequest == null}.
 * @param model            Gemini model identifier (e.g. "gemini-2.5-flash",
 *                         "gemini-3.1-flash-lite"). MUST be non-null and
 *                         non-blank — the {@link GeminiClient} interpolates
 *                         this directly into the generateContent URL, and a
 *                         null/blank value would produce a malformed request.
 *                         {@link ModelResolver} is the canonical source. Tests
 *                         must pass an explicit model string. The
 *                         {@link CacheRequest#model} on the embedded
 *                         {@code cacheRequest} (when present) MUST match this
 *                         value — they're paired so the cacheKey hash and the
 *                         actual generateContent call agree on which model is
 *                         being used.
 */
@RegisterForReflection
public record GeminiRequest(
        String taskName,
        String systemPrompt,
        String userPrompt,
        int maxOutputTokens,
        int thinkingBudget,
        Map<String, Object> responseSchema,
        CacheRequest cacheRequest,
        String uncachedPrompt,
        String model) {

    /**
     * Compact canonical constructor — enforces the cacheRequest/uncachedPrompt
     * invariant at every construction site (not just via
     * {@link #withCacheRequest}). Records auto-generate the canonical
     * constructor as public, so a direct
     * {@code new GeminiRequest(..., cacheReq, null)} would otherwise compile
     * and produce an invalid request; {@link GeminiClient} would then late-NPE
     * on cache miss when it passes the null {@code uncachedPrompt} into
     * {@code Map.of("text", null)} — violating AC-9.3 (cache failures must be
     * invisible to the user). The check here makes that bypass impossible.
     *
     * <p>Also rejects null/blank {@code model} — same reasoning, applied to
     * the generateContent URL build. A null model would produce
     * {@code /v1beta/models/null:generateContent} which Gemini 404s.
     */
    public GeminiRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(
                    "model must be non-null and non-blank — pass the result of "
                            + "ModelResolver.modelFor(TaskMetadata.TASK_*). A null/blank "
                            + "model would build a malformed Gemini URL.");
        }
        if (cacheRequest != null && uncachedPrompt == null) {
            throw new NullPointerException(
                    "uncachedPrompt must be non-null when cacheRequest is set — "
                            + "this is the FULL user message for the cache-miss fallback. "
                            + "Without it, GeminiClient would send only the dynamic userPrompt "
                            + "on miss and silently drop the cacheable prefix (CC-4a CRITICAL #1).");
        }
    }

    /**
     * Convenience factory for callers that don't need responseSchema or caching.
     * Both reserved fields default to {@code null}.
     *
     * <p>The {@code model} argument is mandatory — see the canonical constructor
     * Javadoc. Callers obtain it from {@link ModelResolver#modelFor(String)} or
     * pass a literal in tests.
     */
    public static GeminiRequest of(String taskName,
                                   String systemPrompt,
                                   String userPrompt,
                                   int maxOutputTokens,
                                   int thinkingBudget,
                                   String model) {
        return new GeminiRequest(taskName, systemPrompt, userPrompt,
                maxOutputTokens, thinkingBudget, null, null, null, model);
    }

    /**
     * Return a copy of this request with {@code responseSchema} populated.
     * Used by RS-2..RS-6 callers to enable Gemini server-side structured
     * output enforcement. Pass the result of
     * {@link ResponseSchemas#schemaForManagerGolden()} (or a sibling).
     */
    public GeminiRequest withResponseSchema(Map<String, Object> schema) {
        return new GeminiRequest(taskName, systemPrompt, userPrompt,
                maxOutputTokens, thinkingBudget, schema, cacheRequest, uncachedPrompt, model);
    }

    /**
     * Return a copy of this request with {@code cacheRequest} + {@code uncachedPrompt}
     * populated. Used by CC-4 callers to opt into the Gemini context cache.
     *
     * <p>The two-arg signature is intentional: passing a cacheRequest without an
     * uncached fallback prompt would silently send Gemini the dynamic-only
     * userPrompt on cache miss, dropping the static portion (the CC-4a
     * CRITICAL #1 bug). Making both args mandatory makes that mistake a
     * compile-time / fail-loud error instead of a silent quality regression.
     *
     * <p>{@code uncachedPrompt} is the caller's full pre-rendered user message
     * — exactly what would be sent if caching were off, in whatever byte order
     * the caller prefers (dynamic+static for QUESTION_SUGGEST, possibly
     * static+dynamic for other tasks). {@link GeminiClient} sends it verbatim
     * when {@link GeminiCacheService#resolveOrCreate(CacheRequest)} returns
     * null.
     *
     * @throws NullPointerException if {@code cacheReq} is null at the builder, or
     *         if {@code uncachedPrompt} is null (delegated to the compact canonical
     *         constructor — single source of truth for the cache invariant).
     */
    public GeminiRequest withCacheRequest(CacheRequest cacheReq, String uncachedPrompt) {
        if (cacheReq == null) {
            throw new NullPointerException(
                    "cacheRequest must not be null when calling withCacheRequest");
        }
        // uncachedPrompt validation is delegated to the canonical constructor
        // below so direct-ctor callers can't bypass it either. The compact ctor
        // throws NullPointerException with the full diagnostic.
        return new GeminiRequest(taskName, systemPrompt, userPrompt,
                maxOutputTokens, thinkingBudget, responseSchema, cacheReq, uncachedPrompt, model);
    }
}

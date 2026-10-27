package com.catalystflow.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Single shared HTTP client + retry loop for every Gemini call in the
 * ai-service module (RS-1, claw-back for the GeminiClient extraction
 * flagged in ai-service/pom.xml and the duplicate-callGemini comment in
 * {@link ManagerGuidanceService}).
 *
 * <p>Pre-RS-1 this code was duplicated across {@code AiReviewService.callGemini}
 * and {@code ManagerGuidanceService.callGemini}. After RS-1 both services
 * inject {@link GeminiClient} and call {@link #call(GeminiRequest)}.
 *
 * <h2>Retry contract</h2>
 * <ul>
 *   <li>200 → return.</li>
 *   <li>5xx → retry ONCE (1 s sleep), then surface {@code BAD_GATEWAY}.</li>
 *   <li>429 → retry up to {@link #MAX_429_RETRIES} with Retry-After-aware
 *       backoff. On exhaustion, throw {@link GeminiRateLimitedException} so
 *       {@code AiWorkerHandler} can defer via SQS ChangeMessageVisibility
 *       rather than mark FAILED immediately.</li>
 *   <li>Other 4xx → no retry, throw {@code WebApplicationException(BAD_GATEWAY)}.</li>
 *   <li>{@link HttpTimeoutException} → no retry; the 500 s budget already
 *       elapsed, surface {@code GATEWAY_TIMEOUT}.</li>
 *   <li>{@link java.io.IOException} → retry ONCE, then {@code SERVICE_UNAVAILABLE}.</li>
 * </ul>
 *
 * <h2>Response handling</h2>
 * <ul>
 *   <li>Captures {@code usageMetadata} BEFORE any throw path so failed jobs
 *       (truncated / blocked / no-candidates) still get tokenIn/tokenOut
 *       persisted via {@link GeminiTelemetryException} — Gemini bills for
 *       tokens generated regardless of finishReason.</li>
 *   <li>{@code MAX_TOKENS} / {@code SAFETY} / {@code RECITATION} /
 *       {@code BLOCKED} / {@code PROHIBITED_CONTENT} → throw
 *       {@link GeminiTelemetryException} with usage attached.</li>
 *   <li>Thinking parts (marked {@code thought:true}) are skipped when
 *       concatenating the response text.</li>
 * </ul>
 *
 * <h2>Cacheable + schema-enforced calls</h2>
 * Two optional behaviours layered on top of the base call:
 * <ul>
 *   <li>{@link GeminiRequest#responseSchema} (RS-2..RS-6): when non-null,
 *       added to {@code generationConfig.response_json_schema} so Gemini
 *       enforces the response shape at decode time. RJS-1 (2026-05-25)
 *       switched from the legacy {@code responseSchema} field (OpenAPI
 *       subset) to {@code response_json_schema} (full JSON Schema 2020-12)
 *       so {@code additionalProperties} on the citations map is enforced
 *       server-side instead of post-hoc.</li>
 *   <li>{@link GeminiRequest#cacheRequest} (CC-3): when non-null, this class
 *       consults {@link GeminiCacheService#resolveOrCreate} to discover a
 *       Gemini {@code cachedContents/<id>} for the cacheable prefix and
 *       sends {@code cachedContent: <name>} in the request body. On a
 *       null resolve (cache layer degraded) the request falls through to
 *       the uncached body unchanged from RS-1 — AC-9.3 cache failures must
 *       be invisible to the user-facing call.</li>
 * </ul>
 */
@ApplicationScoped
public class GeminiClient {

    private static final Logger LOG = Logger.getLogger(GeminiClient.class);

    /**
     * Max number of 429 retries (in addition to the original attempt). 2 retries
     * = 3 total attempts on a rate-limited response. Gemini's quota windows are
     * typically per-minute so a few seconds of backoff is usually enough to
     * clear a short burst. Added 2026-05-20 alongside the AiWorker fan-out
     * change (BatchSize 1→10, reserved 5→25) which raises peak in-flight calls
     * from 5 to ~250 and therefore the probability of hitting per-minute quota.
     *
     * <p>Public so {@code AiWorkerHandler} can include it in the final-attempt
     * error message that lands on the AiJobs row before DLQ.
     */
    public static final int MAX_429_RETRIES = 2;

    /** Cap for exponential backoff when no Retry-After header is present. */
    private static final long MAX_429_BACKOFF_MS = 8_000L;

    /**
     * Per-attempt HTTP timeout for the Gemini call. 500 s is intentionally
     * generous — the work is already on an async SQS-backed worker, and a
     * tight client-side timeout was killing legitimate Gemini responses for
     * larger INSIGHTS prompts (incident 2026-05-17, job b4508ab5…).
     * Paired with the AiWorker Lambda Timeout=540 s in template.yaml.
     */
    private static final Duration GEMINI_HTTP_TIMEOUT = Duration.ofSeconds(500);

    String geminiApiKey = "";

    @Inject
    void configureGeminiApiKey(@ConfigProperty(name = "gemini.api-key") java.util.Optional<String> configuredKey) {
        geminiApiKey = configuredKey.orElse("");
    }

    // Model is no longer injected here — it travels per-request on
    // GeminiRequest.model (sourced from ModelResolver.modelFor()). The URL
    // build and parseResponse both read it from the request now, so a single
    // GeminiClient instance can route different task types to different
    // Gemini models without any field-level config.

    @ConfigProperty(name = "ai-review.log-llm-previews", defaultValue = "false")
    boolean logLlmPreviews;

    /**
     * CC-5: deploy stage tag for the EMF metric. Read from env (SAM templates
     * inject {@code CATALYSTFLOW_STAGE: prod | dev | local}). Falls back to
     * {@code "local"} when unset so dev / sam-local emissions still show up
     * in CloudWatch with a distinguishable dimension.
     */
    @ConfigProperty(name = "catalystflow.stage", defaultValue = "local")
    String stage;

    @Inject
    ObjectMapper mapper;

    /**
     * Package-private (not private) so {@code GeminiClientTest} can swap in a
     * Mockito mock — {@code HttpClient} is not a CDI bean, so {@code @InjectMock}
     * does NOT work against this field. The test mirrors the same approach used
     * by {@code AiReviewServiceRateLimitTest} on this branch.
     */
    HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * CC-5 raw EMF sink. Defaults to {@link System#out} so the rendered log
     * event is a bare JSON object — Lambda's CloudWatch Logs integration ships
     * stdout to CW Logs verbatim (no level/category prefix), and CloudWatch's
     * EMF parser accepts it as embedded metrics.
     *
     * <p>Pre-fix this used {@code LOG.info(emfJson)} via JBoss Logger, which
     * prepends a timestamp + level + category — CW's EMF parser then sees
     * {@code "2026-05-24 ... INFO  [com.fai...] {...}"} and rejects the line
     * as non-JSON, silently dropping the metric. The reviewer caught this:
     * "EMF will not parse as embedded metrics".
     *
     * <p>Package-private so tests can swap in a {@link java.io.ByteArrayOutputStream}-backed
     * sink and assert the rendered bytes are parseable JSON with a valid
     * {@code _aws} envelope (see {@code GeminiClientTest.emitCacheMetric_*}).
     */
    java.io.PrintStream emfSink = System.out;

    /**
     * CC-3: resolves the per-request cache opt-in to a Gemini
     * {@code cachedContents/<id>} name. Null cacheRequest → uncached.
     * resolveOrCreate returns null → cache layer degraded, fall through
     * to uncached path. The cycle that would have formed with
     * {@code GeminiClient ↔ GeminiCacheService} is already broken by
     * {@link GeminiCacheTransport} (CC-2 follow-up).
     */
    @Inject
    GeminiCacheService cacheService;

    /**
     * Composer used by {@link #safeCacheKeyForLog} to render the cacheKey for
     * the AC-9.5 log line. Defaults to
     * {@link GeminiCacheService#composeCacheKey}; a test seam — overridable
     * via direct field assignment to inject a throwing composer for
     * regression-testing the log-path protection. Quarkus's default subclass
     * mock-maker can't intercept static method calls, so this Function field
     * is how we prove end-to-end that a thrown composeCacheKey does not
     * escape past {@link #safeCacheKeyForLog}.
     */
    Function<CacheRequest, String> cacheKeyComposer = GeminiCacheService::composeCacheKey;

    public GeminiResult call(GeminiRequest req) {
        // CC-3 step 1: resolve the cache opt-in (if any). Null cacheRequest
        // or null/blank result from resolveOrCreate all mean "send uncached".
        String resolvedCacheName = null;
        if (req.cacheRequest() != null) {
            try {
                resolvedCacheName = cacheService.resolveOrCreate(req.cacheRequest());
            } catch (Exception e) {
                // GeminiCacheService is contracted to return null rather than
                // throw, but defence-in-depth — never let cache layer break
                // the user-facing call.
                LOG.warnf(e, "Gemini cache resolveOrCreate threw for task=%s; falling through to uncached",
                        req.taskName());
                resolvedCacheName = null;
            }
        }
        // Single source of truth: cacheHit AND the body-construction
        // discriminator both check non-null + non-blank. Pre-fix the body
        // had its own blank check while cacheHit only checked null —
        // a blank name from the cache service would have produced
        // cacheHit=true on an uncached body (AC-9.4 violation).
        boolean cacheHit = resolvedCacheName != null && !resolvedCacheName.isBlank();
        String effectiveCacheName = cacheHit ? resolvedCacheName : null;

        // AC-9.5: log line per call so dashboards (CC-5's EMF metric) can
        // compute hit-rate. Field order + count are STABLE across all paths
        // so log-based parsers don't have to handle conditional fields:
        //   task=<X> cacheHit=<bool> cacheKey=<id|[none]> cacheable=<bool>
        // [none] sentinel marks non-cacheable calls (no cacheRequest);
        // composition is wrapped in safeCacheKeyForLog so observability
        // code can NEVER leak through and break the user-facing call (AC-9.3).
        LOG.infof("Gemini call task=%s cacheHit=%s cacheKey=%s cacheable=%s",
                req.taskName(),
                cacheHit,
                req.cacheRequest() != null
                        ? safeCacheKeyForLog(req.cacheRequest())
                        : "[none]",
                req.cacheRequest() != null);

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + req.model() + ":generateContent";
        HttpRequest httpRequest = buildPost(url, buildRequestBody(req, effectiveCacheName));

        HttpResponse<String> httpResponse;
        try {
            httpResponse = sendWithRetries(httpRequest, req.taskName());
        } catch (CacheUnusableException unusable) {
            // The sent cachedContent is unusable (model mismatch, reaped/deleted,
            // or permission). Cache-aside recovery: evict the stale row so the NEXT
            // call recreates a fresh cache, then retry THIS call ONCE uncached.
            LOG.warnf("Gemini task=%s cachedContent unusable — evicting + retrying once uncached: %s",
                    req.taskName(), unusable.getMessage());
            evictCacheRow(req);
            HttpRequest uncached = buildPost(url, buildRequestBody(req, null));
            HttpResponse<String> retryResponse;
            try {
                retryResponse = sendWithRetries(uncached, req.taskName());
            } catch (CacheUnusableException persisted) {
                // Unreachable (the uncached body has no cachedContent), but the
                // signal must never escape this class — surface terminal.
                throw new WebApplicationException(
                        "AI service error (upstream): cachedContent unusable after uncached retry",
                        Response.Status.BAD_GATEWAY);
            }
            return parseResponse(retryResponse, req.taskName(), false, req.cacheRequest(), req.model());
        }
        return parseResponse(httpResponse, req.taskName(), cacheHit, req.cacheRequest(), req.model());
    }

    /**
     * Streaming variant of {@link #call(GeminiRequest)}. Uses Gemini's
     * {@code :streamGenerateContent?alt=sse} endpoint and invokes
     * {@code chunkHandler} with the cumulative non-thought text every ~500ms
     * or ~50 tokens, whichever first.
     *
     * <p>Used today only by the INSIGHTS task — the long-narrative output
     * (~2k tokens, ~30-50s at Gemini Flash output rate) benefits from
     * progressive UI rendering. ANALYZE / GOLDEN / DIGEST / QUESTION_SUGGEST
     * keep using {@link #call(GeminiRequest)}.
     *
     * <h3>Retry contract</h3>
     * <p>Streaming intentionally has NO in-stream retries — losing partial
     * buffer state to a transient failure is more confusing than failing the
     * whole job and letting SQS redeliver. Terminal failures (non-200 status,
     * HTTP I/O exception, timeout) bubble up identically to {@link #call}.
     *
     * <h3>Caller contract for chunkHandler</h3>
     * <p>The handler MUST be best-effort — exceptions thrown from it are
     * caught and logged at WARN; they do not abort the stream. This matches
     * the {@link com.catalystflow.ai.repository.AiJobRepository#updatePartialText}
     * contract on the typical caller side (preview is best-effort; terminal
     * state wins).
     *
     * @param req           same shape as {@link #call} — cacheRequest /
     *                      responseSchema / model all honored.
     * @param chunkHandler  invoked with the cumulative text-buffer string
     *                      every ~500ms or ~50 new tokens. Cumulative, not
     *                      delta — caller can just overwrite their UI state
     *                      each time without offset tracking.
     */
    public GeminiResult callStreaming(GeminiRequest req, Consumer<String> chunkHandler) {
        // Cache resolution: mirror of call() — same opt-in semantics, same
        // degrade-on-failure behavior. Streaming doesn't change cache logic.
        String resolvedCacheName = null;
        if (req.cacheRequest() != null) {
            try {
                resolvedCacheName = cacheService.resolveOrCreate(req.cacheRequest());
            } catch (Exception e) {
                LOG.warnf(e, "Gemini cache resolveOrCreate threw for streaming task=%s; falling through to uncached",
                        req.taskName());
                resolvedCacheName = null;
            }
        }
        boolean cacheHit = resolvedCacheName != null && !resolvedCacheName.isBlank();
        String effectiveCacheName = cacheHit ? resolvedCacheName : null;
        boolean cacheable = req.cacheRequest() != null;

        LOG.infof("Gemini callStreaming task=%s cacheHit=%s cacheKey=%s cacheable=%s",
                req.taskName(),
                cacheHit,
                cacheable ? safeCacheKeyForLog(req.cacheRequest()) : "[none]",
                cacheable);

        // ?alt=sse forces Server-Sent Events framing (`data: {json}\n\n` per
        // event). Without it Gemini emits a single JSON array which is harder
        // to parse incrementally with java.net.http's BufferedReader.
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + req.model() + ":streamGenerateContent?alt=sse";
        HttpRequest httpRequest = buildPost(url, buildRequestBody(req, effectiveCacheName));

        // Pre-stream send + non-200 handling goes through a single-retry loop
        // (sendStreamingWithRetry). Retrying the *initial* non-200 is safe — no
        // chunk has been emitted yet, so the "no in-stream retry once bytes flow"
        // contract (failures during parsing below) is preserved. Transient 5xx
        // (500/502/503) that survive the retry, and post-retry IO failures, are
        // deferred via GeminiTransientUpstreamException instead of failing hard.
        // A cross-model cachedContent 400 self-heals: drop the cachedContent and
        // retry once uncached (pre-stream, so no bytes have been emitted yet).
        HttpResponse<java.io.InputStream> httpResponse;
        try {
            httpResponse = sendStreamingWithRetry(httpRequest, req.taskName());
        } catch (CacheUnusableException unusable) {
            LOG.warnf("Gemini streaming task=%s cachedContent unusable — evicting + retrying once uncached: %s",
                    req.taskName(), unusable.getMessage());
            evictCacheRow(req);
            HttpRequest uncached = buildPost(url, buildRequestBody(req, null));
            try {
                httpResponse = sendStreamingWithRetry(uncached, req.taskName());
            } catch (CacheUnusableException persisted) {
                // Unreachable; the signal must never escape this class.
                throw new WebApplicationException(
                        "AI service error (upstream): cachedContent unusable after uncached retry",
                        Response.Status.BAD_GATEWAY);
            }
            cacheHit = false; // the successful stream came from the uncached retry
        }

        // Parse SSE-framed stream. Accumulate text-parts into a buffer, throttle
        // chunkHandler invocations to keep DDB write rate sane.
        StringBuilder buffer = new StringBuilder();
        long lastFlushMs = System.currentTimeMillis();
        int charsSinceFlush = 0;
        Integer tokenIn = null;
        Integer tokenOut = null;
        Integer cachedTokens = null;
        String finishReason = "";
        // Captures any non-SSE-data bytes (e.g. a mid-stream {"error":...} trailer
        // Gemini emits when it aborts a stream) so the post-loop completeness guard
        // can classify the failure rather than silently dropping it.
        StringBuilder errorAccum = new StringBuilder();

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(httpResponse.body(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (!line.startsWith("data: ")) {
                    // Not an SSE data frame. Gemini aborts a stream mid-flight by
                    // emitting a bare JSON {"error":{...}} trailer (e.g. 503 "model
                    // overloaded") rather than a data: event. Capture it so the
                    // post-loop completeness guard can surface the real transient
                    // instead of silently dropping it and defaulting finishReason
                    // to STOP (which surfaces the truncated buffer as MALFORMED).
                    errorAccum.append(line).append('\n');
                    continue;
                }
                String json = line.substring(6).trim();
                if (json.isEmpty() || "[DONE]".equals(json)) continue;

                JsonNode chunk;
                try {
                    chunk = mapper.readTree(json);
                } catch (Exception e) {
                    LOG.warnf(e, "Gemini streaming task=%s skipping unparseable chunk: %s",
                            req.taskName(), json.substring(0, Math.min(json.length(), 200)));
                    continue;
                }

                // usageMetadata appears on the final chunk(s) — capture as we see it.
                JsonNode usage = chunk.path("usageMetadata");
                if (!usage.isMissingNode()) {
                    if (usage.has("promptTokenCount") && !usage.get("promptTokenCount").isNull()) {
                        tokenIn = usage.get("promptTokenCount").asInt();
                    }
                    if (usage.has("candidatesTokenCount") && !usage.get("candidatesTokenCount").isNull()) {
                        tokenOut = usage.get("candidatesTokenCount").asInt();
                    }
                    if (usage.has("cachedContentTokenCount") && !usage.get("cachedContentTokenCount").isNull()) {
                        cachedTokens = usage.get("cachedContentTokenCount").asInt();
                    }
                }

                JsonNode candidates = chunk.path("candidates");
                if (candidates.isArray() && candidates.size() > 0) {
                    JsonNode firstCandidate = candidates.get(0);
                    String fr = firstCandidate.path("finishReason").asText("");
                    if (!fr.isEmpty()) finishReason = fr;

                    JsonNode parts = firstCandidate.path("content").path("parts");
                    if (parts.isArray()) {
                        for (JsonNode part : parts) {
                            if (part.has("thought") && part.get("thought").asBoolean()) continue;
                            String text = part.path("text").asText("");
                            if (!text.isEmpty()) {
                                buffer.append(text);
                                charsSinceFlush += text.length();
                            }
                        }
                    }
                }

                // Throttle: flush if 500ms elapsed OR ~50 tokens (~200 chars) accumulated.
                long now = System.currentTimeMillis();
                if (buffer.length() > 0 && (now - lastFlushMs >= 500 || charsSinceFlush >= 200)) {
                    invokeHandlerSafely(chunkHandler, buffer.toString(), req.taskName());
                    lastFlushMs = now;
                    charsSinceFlush = 0;
                }
            }
        } catch (java.io.IOException e) {
            LOG.errorf(e, "Gemini streaming task=%s aborted mid-stream", req.taskName());
            throw new WebApplicationException("AI service streaming aborted",
                    Response.Status.BAD_GATEWAY);
        }

        // Final flush — any sub-500ms / sub-50-token tail must reach the handler too,
        // otherwise the last few sentences never make it to DDB.
        if (buffer.length() > 0) {
            invokeHandlerSafely(chunkHandler, buffer.toString(), req.taskName());
        }

        // A normally-completed Gemini stream ALWAYS terminates with a finishReason
        // on the final candidate. If we never received one, the stream was cut
        // mid-flight (an upstream 503 or a dropped connection — the {"error":...}
        // trailer, when present, is in errorAccum). Returning the partial buffer as
        // a STOP "success" makes the caller parse truncated JSON and fail TERMINAL
        // as MALFORMED, when the real condition is a RETRYABLE transient. Surface it
        // so AiWorkerHandler defers via SQS (and the SPEC-503 redelivery flips to
        // the fallback model) — the same handling sendStreamingWithRetry gives a
        // 503 that arrives BEFORE the stream opens, which a mid-stream 503 bypasses.
        if (finishReason.isEmpty()) {
            int upstreamCode = parseUpstreamErrorCode(errorAccum.toString());
            LOG.errorf("Gemini streaming task=%s interrupted before completion — no terminal "
                            + "finishReason (%d chars buffered, tokenOut=%s). upstreamError=%s",
                    req.taskName(), buffer.length(),
                    tokenOut == null ? "unknown" : tokenOut,
                    errorAccum.length() == 0 ? "(none)" : errorAccum.toString().trim());
            emitCacheMetric(req.taskName(), req.cacheRequest(), cacheHit, cacheable,
                    tokenIn, tokenOut, cachedTokens);
            if (upstreamCode == 429) {
                throw new GeminiRateLimitedException(
                        "RATE_LIMITED: Gemini streaming interrupted mid-flight (429)", 0L);
            }
            throw new GeminiTransientUpstreamException(
                    "AI service error (upstream): streaming response interrupted before completion"
                            + (upstreamCode > 0 ? " (" + upstreamCode + ")" : ""),
                    upstreamCode > 0 ? upstreamCode : 0, 0L);
        }
        String effectiveFinishReason = finishReason;

        // Mirror call()'s gates: MAX_TOKENS and safety/recitation/blocked → throw
        // GeminiTelemetryException so the worker's catch chain persists token spend.
        if ("MAX_TOKENS".equals(finishReason)) {
            LOG.warnf("Gemini streaming task=%s truncated by token cap. tokenIn=%s tokenOut=%s",
                    req.taskName(), tokenIn, tokenOut);
            emitCacheMetric(req.taskName(), req.cacheRequest(), cacheHit, cacheable,
                    tokenIn, tokenOut, cachedTokens);
            throw new GeminiTelemetryException(
                    "Response truncated by token limit — increase maxOutputTokens",
                    Response.Status.BAD_GATEWAY, tokenIn, tokenOut, req.model(),
                    cacheHit, cachedTokens);
        }
        if ("SAFETY".equals(finishReason) || "RECITATION".equals(finishReason)
                || "BLOCKED".equals(finishReason) || "PROHIBITED_CONTENT".equals(finishReason)) {
            LOG.warnf("Gemini streaming task=%s refused. finishReason=%s tokenIn=%s tokenOut=%s",
                    req.taskName(), finishReason, tokenIn, tokenOut);
            emitCacheMetric(req.taskName(), req.cacheRequest(), cacheHit, cacheable,
                    tokenIn, tokenOut, cachedTokens);
            throw new GeminiTelemetryException(
                    "Response blocked by upstream safety filter (" + finishReason + ")",
                    Response.Status.BAD_GATEWAY, tokenIn, tokenOut, req.model(),
                    cacheHit, cachedTokens);
        }

        LOG.infof("Gemini streaming call task=%s model=%s finishReason=%s tokenIn=%s tokenOut=%s cachedTokens=%s responseChars=%d",
                req.taskName(), req.model(), effectiveFinishReason,
                tokenIn == null ? "unknown" : tokenIn,
                tokenOut == null ? "unknown" : tokenOut,
                cachedTokens == null ? "unknown" : cachedTokens,
                buffer.length());
        if (logLlmPreviews) {
            LOG.infof("Gemini streaming response preview task=%s preview=%s",
                    req.taskName(), redactedPreview(buffer.toString()));
        }
        emitCacheMetric(req.taskName(), req.cacheRequest(), cacheHit, cacheable,
                tokenIn, tokenOut, cachedTokens);
        return new GeminiResult(buffer.toString(), tokenIn, tokenOut,
                effectiveFinishReason, req.model(), cacheHit, cachedTokens);
    }

    /**
     * Defensive wrapper for the streaming chunkHandler. The handler is
     * observability-adjacent (writes to DDB partialText for UX preview); a
     * thrown exception there must not abort the underlying Gemini call —
     * same contract as the EMF emit at the end of {@link #call}.
     */
    private void invokeHandlerSafely(Consumer<String> chunkHandler, String snapshot, String taskName) {
        try {
            chunkHandler.accept(snapshot);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Gemini streaming chunkHandler threw for task=%s — continuing (preview only)",
                    taskName);
        }
    }

    /**
     * Assemble the Gemini request body. {@code effectiveCacheName} is the
     * caller-vetted cache name (already null-and-blank-checked by
     * {@link #call}).
     *
     * <p>Three paths:
     * <ul>
     *   <li><b>Cache HIT</b> ({@code effectiveCacheName != null}): omit
     *       {@code system_instruction} (it's part of the cached prefix), add
     *       {@code cachedContent: <name>} at top level. The per-call
     *       {@code req.userPrompt()} (dynamic-only) is sent as the user
     *       message; Gemini prepends the cached prefix automatically.</li>
     *   <li><b>Cache MISS with caller intent</b>
     *       ({@code effectiveCacheName == null && req.cacheRequest() != null}):
     *       the cache layer degraded (too-small content, DDB outage, Gemini
     *       4xx, ...). The caller passed {@code req.uncachedPrompt()} — their
     *       full pre-rendered user message in the byte order they want for
     *       this task. Send it verbatim. No reconstruction inside this client
     *       — the previous CC-4a implementation appended
     *       {@code cacheRequest.extraPrefix()} after {@code userPrompt}, which
     *       baked QUESTION_SUGGEST's dynamic-then-static order into a shared
     *       client and would have broken any future task whose cache-OFF
     *       order is different.</li>
     *   <li><b>Plain uncached</b> ({@code req.cacheRequest() == null}): body
     *       shape unchanged from RS-1 — system_instruction + userPrompt.</li>
     * </ul>
     */
    private Map<String, Object> buildRequestBody(GeminiRequest req, String effectiveCacheName) {
        Map<String, Object> requestBody = new LinkedHashMap<>();

        if (effectiveCacheName != null) {
            requestBody.put("cachedContent", effectiveCacheName);
        } else {
            requestBody.put("system_instruction",
                    Map.of("parts", List.of(Map.of("text", req.systemPrompt()))));
        }

        // User-message selection — caller-owned byte order on cache miss.
        // Cache-hit path: userPrompt is the dynamic-only portion; Gemini
        //   auto-prepends the cached prefix. Send userPrompt verbatim.
        // Cache-miss path with cacheRequest: use uncachedPrompt (the caller's
        //   full pre-rendered message). GeminiRequest.withCacheRequest
        //   enforces that uncachedPrompt is non-null in this scenario, so no
        //   defensive fallback is needed here — if it ever IS null at this
        //   point, the call would have failed at the builder.
        // Plain uncached (no cacheRequest): userPrompt is the full message.
        String userContent;
        if (effectiveCacheName == null && req.cacheRequest() != null) {
            userContent = req.uncachedPrompt();
        } else {
            userContent = req.userPrompt();
        }

        requestBody.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", userContent)))));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", 0.2);
        generationConfig.put("maxOutputTokens", req.maxOutputTokens());
        // responseMimeType=application/json forces Gemini to return raw JSON
        // rather than markdown-fenced prose.
        generationConfig.put("responseMimeType", "application/json");
        // thinkingConfig caps the 2.5-series reasoning budget so internal
        // reasoning can't eat the visible-output budget — see incident
        // feac9e10 (2026-05-18).
        generationConfig.put("thinkingConfig", Map.of("thinkingBudget", req.thinkingBudget()));
        // RJS-1 (2026-05-25): switched from legacy `responseSchema` (OpenAPI
        // subset, no additionalProperties) to `response_json_schema` (full JSON
        // Schema 2020-12, supports additionalProperties / oneOf / $ref). Gemini
        // 2.5+ supports both; the two fields are mutually exclusive — emit only
        // one. We need the new field so citationsMapSchema can re-introduce
        // additionalProperties for server-side enforcement of citation entry
        // shape (RJS-1 ResponseSchemas changes).
        if (req.responseSchema() != null) {
            generationConfig.put("response_json_schema", req.responseSchema());
        }
        requestBody.put("generationConfig", generationConfig);

        return requestBody;
    }

    /** Serialize {@code requestBody} and build the Gemini POST to {@code url}. */
    private HttpRequest buildPost(String url, Map<String, Object> requestBody) {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            throw new WebApplicationException("Review assistance is not configured", Response.Status.SERVICE_UNAVAILABLE);
        }
        String body;
        try {
            body = mapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            // Serialising our own request object can't fail in practice — but if
            // it does it's a code bug, not a transient upstream issue.
            throw new WebApplicationException("AI service error: request build failed",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", geminiApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(GEMINI_HTTP_TIMEOUT)
                .build();
    }

    /**
     * True when a non-200 means the sent cachedContent is UNUSABLE — the call
     * should drop it and retry uncached, and the stale row should be evicted.
     * Covers the cache-resource failures Gemini reports as a 4xx referencing the
     * CachedContent:
     * <ul>
     *   <li>cross-model mismatch — "…has to be the same" (400; job 82d9b1f7);</li>
     *   <li>reaped/expired/deleted — "not found" / "does not exist" (400/404);</li>
     *   <li>permission — the cache resource is gone/inaccessible (403).</li>
     * </ul>
     * Detected by body text (the HTTP code alone can't tell a cache failure from
     * an unrelated 4xx); case-insensitive against Gemini wording drift.
     */
    private static boolean isCacheUnusable(int code, String body) {
        if (body == null || code < 400 || code >= 500) return false;
        String b = body.toLowerCase(java.util.Locale.ROOT);
        // Must reference the cachedContent — this guard keeps the (individually
        // generic) phrases below from matching an unrelated 4xx.
        if (!(b.contains("cachedcontent") || b.contains("cached content"))) return false;
        return b.contains("has to be the same")   // cross-model mismatch
                || b.contains("not found")          // reaped / deleted
                || b.contains("does not exist")
                || b.contains("expir")              // expired/expires/expiry — the common TTL case
                || b.contains("no longer")          // "no longer available/exists"
                || b.contains("deleted")
                || b.contains("revoked")
                || b.contains("permission");        // 403 on the cache resource (fail-open: serve uncached)
    }

    /**
     * Internal signal (never escapes this class): the sent cachedContent is
     * unusable (model mismatch, reaped/deleted, or permission). {@link #call} and
     * {@link #callStreaming} catch it, evict the stale cache row, and retry once
     * uncached.
     */
    private static final class CacheUnusableException extends RuntimeException {
        CacheUnusableException(String message) { super(message); }
    }

    /**
     * Best-effort cache-aside eviction after a cache-unusable error: remove the
     * stale row so the NEXT call recreates a fresh cachedContent instead of
     * re-hitting the dead one. Never throws.
     */
    private void evictCacheRow(GeminiRequest req) {
        if (req.cacheRequest() == null) return;
        try {
            cacheService.delete(GeminiCacheService.composeCacheKey(req.cacheRequest()));
        } catch (Exception e) {
            LOG.warnf("Gemini task=%s cache evict failed (best-effort): %s", req.taskName(), e.getMessage());
        }
    }

    /**
     * Drives the HTTP retry loop. Returns the successful 200 response; throws
     * one of the documented exception types on any non-200 terminal path.
     */
    private HttpResponse<String> sendWithRetries(HttpRequest httpRequest, String taskName) {
        HttpResponse<String> httpResponse = null;
        Exception lastTransient = null;
        int httpRetries = 0;           // 5xx retries used (max 1)
        int ioRetries = 0;             // IOException retries used (max 1)
        int rate429Retries = 0;        // 429 retries used (max MAX_429_RETRIES)
        // Safety cap covers the worst legitimate path: 1 attempt + 1 5xx retry +
        // MAX_429_RETRIES 429 retries + 1 IO retry. Anything beyond is a code bug.
        final int maxTotalAttempts = 2 + MAX_429_RETRIES + 1;
        int attempt = 0;
        while (attempt < maxTotalAttempts) {
            attempt++;
            try {
                httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                int code = httpResponse.statusCode();
                if (code == 200) {
                    return httpResponse;
                }
                if (code == 429) {
                    if (rate429Retries >= MAX_429_RETRIES) {
                        long retryAfterMs = parseRetryAfterMs(httpResponse).orElse(0L);
                        LOG.errorf("Gemini task=%s rate-limited (429) — exhausted %d in-process retries; Retry-After=%dms (deferring to SQS)",
                                taskName, MAX_429_RETRIES, retryAfterMs);
                        throw new GeminiRateLimitedException(
                                "RATE_LIMITED: Gemini quota exceeded after " + (MAX_429_RETRIES + 1)
                                        + " in-process attempts (Retry-After=" + retryAfterMs + "ms)",
                                retryAfterMs / 1000L);
                    }
                    long backoffMs = parseRetryAfterMs(httpResponse)
                            .orElse(Math.min(2000L * (1L << rate429Retries), MAX_429_BACKOFF_MS));
                    LOG.warnf("Gemini task=%s returned 429 — backing off %dms (retry %d of %d)",
                            taskName, backoffMs, rate429Retries + 1, MAX_429_RETRIES);
                    sleepQuiet(backoffMs);
                    rate429Retries++;
                    continue;
                }
                if (code >= 500 && code <= 599 && httpRetries < 1) {
                    LOG.warnf("Gemini task=%s returned %d on attempt %d — retrying once",
                            taskName, code, attempt);
                    sleepQuiet(1000);
                    httpRetries++;
                    continue;
                }
                // After the single in-process 5xx retry (or on first sight of a
                // non-retryable status): a retryable transient upstream error
                // (500/502/503 — e.g. 503 "model overloaded") is deferred via SQS
                // through GeminiTransientUpstreamException, mirroring the 429 path.
                // Everything else (other 4xx, 504, non-transient 5xx like 501) stays
                // a terminal BAD_GATEWAY that classifyError tags UPSTREAM_5XX/4XX.
                LOG.errorf("Gemini task=%s returned %d: %s", taskName, code, httpResponse.body());
                if (isTransientUpstream(code)) {
                    long retryAfterSec = parseRetryAfterMs(httpResponse).orElse(0L) / 1000L;
                    throw new GeminiTransientUpstreamException(
                            "AI service error (upstream " + code + ")", code, retryAfterSec);
                }
                if (isCacheUnusable(code, httpResponse.body())) {
                    throw new CacheUnusableException(
                            "AI service error (upstream " + code + "): cachedContent unusable");
                }
                throw new WebApplicationException("AI service error (upstream " + code + ")",
                        Response.Status.BAD_GATEWAY);
            } catch (HttpTimeoutException e) {
                // The 500 s budget elapsed — surface immediately, no retry.
                LOG.errorf(e, "Gemini task=%s timed out after %s", taskName, GEMINI_HTTP_TIMEOUT);
                throw new WebApplicationException("AI service timeout",
                        Response.Status.GATEWAY_TIMEOUT);
            } catch (java.io.IOException e) {
                lastTransient = e;
                if (ioRetries < 1) {
                    LOG.warnf("Gemini task=%s IO failure on attempt %d (%s) — retrying once",
                            taskName, attempt, e.getClass().getSimpleName());
                    sleepQuiet(1000);
                    ioRetries++;
                    continue;
                }
                // Post-retry IO failure reaching Gemini is transient — defer via
                // SQS (upstreamCode 0 signals "network/IO", not an HTTP status).
                LOG.errorf(e, "Gemini task=%s IO failure after retry — deferring via transient-upstream", taskName);
                throw new GeminiTransientUpstreamException(
                        "AI service unavailable (IO: " + e.getClass().getSimpleName() + ")", 0, 0L);
            } catch (WebApplicationException | GeminiRateLimitedException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WebApplicationException("AI service interrupted",
                        Response.Status.SERVICE_UNAVAILABLE);
            }
        }
        // Defensive — every loop exit above either returns 200, breaks, or throws.
        LOG.error("Gemini call exhausted retries", lastTransient);
        throw new WebApplicationException("AI service unavailable",
                Response.Status.SERVICE_UNAVAILABLE);
    }

    /**
     * Streaming counterpart to {@link #sendWithRetries}: performs the initial
     * send and returns a guaranteed-200 {@link HttpResponse} whose body
     * {@link java.io.InputStream} the caller then parses as SSE. Applies a single
     * in-process retry for transient upstream 5xx (500/502/503) and for IO
     * failures, exactly like the non-streaming path. Terminal mappings mirror
     * {@link #sendWithRetries}:
     * <ul>
     *   <li>429 → {@link GeminiRateLimitedException} (worker defers via SQS).</li>
     *   <li>transient 5xx after the retry, or post-retry IO →
     *       {@link GeminiTransientUpstreamException} (worker defers via SQS).</li>
     *   <li>other 4xx / 504 / non-transient 5xx → {@code WebApplicationException(BAD_GATEWAY)}.</li>
     *   <li>{@link HttpTimeoutException} → {@code GATEWAY_TIMEOUT} (no retry; budget elapsed).</li>
     * </ul>
     */
    private HttpResponse<java.io.InputStream> sendStreamingWithRetry(HttpRequest httpRequest, String taskName) {
        int httpRetries = 0;   // transient 5xx retries used (max 1)
        int ioRetries = 0;     // IO retries used (max 1)
        final int maxTotalAttempts = 3; // 1 attempt + 1 5xx retry + 1 IO retry headroom
        int attempt = 0;
        while (attempt < maxTotalAttempts) {
            attempt++;
            HttpResponse<java.io.InputStream> httpResponse;
            try {
                httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException e) {
                LOG.errorf(e, "Gemini streaming task=%s timed out after %s", taskName, GEMINI_HTTP_TIMEOUT);
                throw new WebApplicationException("AI service timeout", Response.Status.GATEWAY_TIMEOUT);
            } catch (java.io.IOException e) {
                if (ioRetries < 1) {
                    LOG.warnf("Gemini streaming task=%s IO failure on attempt %d (%s) — retrying once",
                            taskName, attempt, e.getClass().getSimpleName());
                    sleepQuiet(1000);
                    ioRetries++;
                    continue;
                }
                LOG.errorf(e, "Gemini streaming task=%s IO failure after retry — deferring via transient-upstream", taskName);
                throw new GeminiTransientUpstreamException(
                        "AI service unavailable (IO: " + e.getClass().getSimpleName() + ")", 0, 0L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WebApplicationException("AI service interrupted", Response.Status.SERVICE_UNAVAILABLE);
            }

            int code = httpResponse.statusCode();
            if (code == 200) {
                return httpResponse;
            }
            // Non-200: drain a bounded slice of the error body for context (and to
            // release the connection before any retry).
            String errorBody = drainErrorBody(httpResponse);
            if (code == 429) {
                LOG.errorf("Gemini streaming task=%s rate-limited (429): %s", taskName, errorBody);
                throw new GeminiRateLimitedException("RATE_LIMITED: Gemini streaming returned 429", 0L);
            }
            if (code >= 500 && code <= 599 && httpRetries < 1) {
                LOG.warnf("Gemini streaming task=%s returned %d on attempt %d — retrying once: %s",
                        taskName, code, attempt, errorBody);
                sleepQuiet(1000);
                httpRetries++;
                continue;
            }
            if (isTransientUpstream(code)) {
                LOG.errorf("Gemini streaming task=%s returned %d after retry — deferring: %s",
                        taskName, code, errorBody);
                long retryAfterSec = parseRetryAfterMs(httpResponse).orElse(0L) / 1000L;
                throw new GeminiTransientUpstreamException(
                        "AI service error (upstream " + code + ")", code, retryAfterSec);
            }
            LOG.errorf("Gemini streaming task=%s returned %d: %s", taskName, code, errorBody);
            if (isCacheUnusable(code, errorBody)) {
                throw new CacheUnusableException(
                        "AI service error (upstream " + code + "): cachedContent unusable");
            }
            throw new WebApplicationException("AI service error (upstream " + code + ")",
                    Response.Status.BAD_GATEWAY);
        }
        // Defensive — loop always returns 200 or throws above.
        throw new GeminiTransientUpstreamException(
                "AI service unavailable (streaming retries exhausted)", 0, 0L);
    }

    /** Read a bounded slice of a non-200 streaming body for log context, closing the stream. */
    private static String drainErrorBody(HttpResponse<java.io.InputStream> httpResponse) {
        try (java.io.InputStream is = httpResponse.body()) {
            return new String(is.readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(failed to read error body: " + e.getMessage() + ")";
        }
    }

    /**
     * Extract the numeric error code from a Gemini mid-stream error trailer
     * ({@code {"error":{"code":NNN,...}}}). Returns -1 when {@code body} is blank or
     * carries no parseable {@code error.code} — the caller then treats the
     * interruption as an unknown transient (upstreamCode 0). Used only by the
     * {@link #callStreaming} completeness guard, hence instance-scoped (needs the
     * injected {@link #mapper}).
     */
    private int parseUpstreamErrorCode(String body) {
        if (body == null || body.isBlank()) {
            return -1;
        }
        try {
            JsonNode code = mapper.readTree(body.trim()).path("error").path("code");
            return code.isInt() ? code.asInt() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Parse the JSON body, extract usageMetadata first (so failed jobs still
     * carry token telemetry), gate on finishReason, then concatenate non-thought
     * parts.
     */
    private GeminiResult parseResponse(HttpResponse<String> httpResponse, String taskName,
                                       boolean cacheHit, CacheRequest cacheRequest, String model) {
        // cacheable = "this call was eligible for caching" (caller attached a
        // CacheRequest). Threaded into emitCacheMetric as a metric value AND
        // implicitly as a hint for canonical-task-type lookup.
        boolean cacheable = cacheRequest != null;
        try {
            JsonNode responseNode = mapper.readTree(httpResponse.body());

            Integer tokenIn = null;
            Integer tokenOut = null;
            Integer cachedTokens = null;
            JsonNode usage = responseNode.path("usageMetadata");
            if (!usage.isMissingNode()) {
                tokenIn = usage.path("promptTokenCount").asInt(0);
                tokenOut = usage.path("candidatesTokenCount").asInt(0);
                // CC-5 (AC-11.1): Gemini reports the portion of promptTokenCount
                // that was served from the cached prefix in cachedContentTokenCount.
                // Only present on cached calls; missing field → null, which is the
                // honest answer for "we don't know" / "wasn't a cached call".
                JsonNode cached = usage.path("cachedContentTokenCount");
                if (!cached.isMissingNode()) {
                    cachedTokens = cached.asInt(0);
                }
            }

            JsonNode candidates = responseNode.path("candidates");
            if (!candidates.isArray() || candidates.size() == 0) {
                LOG.warnf("Gemini task=%s returned no candidates. model=%s tokenIn=%s tokenOut=%s body=%s",
                        taskName, model, tokenIn, tokenOut, httpResponse.body());
                emitCacheMetric(taskName, cacheRequest, cacheHit, cacheable, tokenIn, tokenOut, cachedTokens);
                throw new GeminiTelemetryException("AI service error: no candidates returned",
                        Response.Status.BAD_GATEWAY, tokenIn, tokenOut, model,
                        cacheHit, cachedTokens);
            }

            String finishReason = candidates.get(0).path("finishReason").asText("");
            if ("MAX_TOKENS".equals(finishReason)) {
                LOG.warnf("Gemini task=%s response truncated by token cap. finishReason=MAX_TOKENS model=%s tokenIn=%s tokenOut=%s",
                        taskName, model, tokenIn, tokenOut);
                emitCacheMetric(taskName, cacheRequest, cacheHit, cacheable, tokenIn, tokenOut, cachedTokens);
                throw new GeminiTelemetryException(
                        "Response truncated by token limit — increase maxOutputTokens",
                        Response.Status.BAD_GATEWAY, tokenIn, tokenOut, model,
                        cacheHit, cachedTokens);
            }
            if ("SAFETY".equals(finishReason) || "RECITATION".equals(finishReason)
                    || "BLOCKED".equals(finishReason) || "PROHIBITED_CONTENT".equals(finishReason)) {
                LOG.warnf("Gemini task=%s refused to complete. finishReason=%s model=%s tokenIn=%s tokenOut=%s",
                        taskName, finishReason, model, tokenIn, tokenOut);
                emitCacheMetric(taskName, cacheRequest, cacheHit, cacheable, tokenIn, tokenOut, cachedTokens);
                throw new GeminiTelemetryException(
                        "Response blocked by upstream safety filter (" + finishReason + ")",
                        Response.Status.BAD_GATEWAY, tokenIn, tokenOut, model,
                        cacheHit, cachedTokens);
            }

            JsonNode parts = candidates.get(0).path("content").path("parts");
            // Thinking models put reasoning in earlier parts marked with
            // "thought:true" — accumulate all non-thought parts.
            StringBuilder text = new StringBuilder();
            for (JsonNode part : parts) {
                if (!part.has("thought") || !part.get("thought").asBoolean()) {
                    text.append(part.path("text").asText());
                }
            }

            String effectiveFinishReason = finishReason.isEmpty() ? "STOP" : finishReason;
            LOG.infof("Gemini call task=%s model=%s finishReason=%s tokenIn=%s tokenOut=%s cachedTokens=%s responseChars=%d",
                    taskName, model, effectiveFinishReason,
                    tokenIn == null ? "unknown" : tokenIn,
                    tokenOut == null ? "unknown" : tokenOut,
                    cachedTokens == null ? "unknown" : cachedTokens,
                    text.length());
            if (logLlmPreviews) {
                LOG.infof("Gemini response preview task=%s preview=%s",
                        taskName, redactedPreview(text.toString()));
            }
            emitCacheMetric(taskName, cacheRequest, cacheHit, cacheable, tokenIn, tokenOut, cachedTokens);
            return new GeminiResult(text.toString(), tokenIn, tokenOut,
                    effectiveFinishReason, model, cacheHit, cachedTokens);
        } catch (WebApplicationException e) {
            // Covers GeminiTelemetryException too (it IS-A WebApplicationException) —
            // these are intentional terminal throws from the gating blocks above,
            // not parse failures. Re-raise verbatim so the caller's catch chain
            // can attach telemetry / classify the error.
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Gemini task=%s response parse failed", taskName);
            throw new WebApplicationException("AI service error: malformed upstream response",
                    Response.Status.BAD_GATEWAY);
        }
    }

    // callCachedContents + CacheCreateResult moved to GeminiCacheTransport
    // in the CC-2 follow-up. Two reasons:
    //   1. AC-8.6 wants the cache HTTP off the user-facing client surface.
    //   2. CC-3 will have GeminiClient.call -> GeminiCacheService.resolveOrCreate.
    //      With callCachedContents still here, GeminiCacheService would inject
    //      GeminiClient → cycle when GeminiClient also injects GeminiCacheService.
    //      Extracting the transport breaks the cycle before it forms.

    /**
     * Compose a cacheKey for log output without ever throwing. Delegates to
     * the swappable {@link #cacheKeyComposer} (default
     * {@link GeminiCacheService#composeCacheKey}). Per AC-9.3 the cache layer
     * must never break the user-facing call — including via observability
     * code. Falls back to {@code [unavailable]} so the log line still
     * produces a parseable record.
     *
     * <p>Package-private (not static) so the regression test can swap the
     * composer field with a throwing function and prove end-to-end that the
     * exception is contained — see
     * {@code GeminiClientTest#call_composeCacheKeyThrows_userFacingCallStillSucceeds}.
     */
    String safeCacheKeyForLog(CacheRequest req) {
        if (req == null) return "[unavailable]";
        try {
            return cacheKeyComposer.apply(req);
        } catch (Exception e) {
            LOG.warnf(e, "Gemini cacheKey log composition failed; using [unavailable]");
            return "[unavailable]";
        }
    }

    /**
     * CC-5 AC-11.2 — emit a CloudWatch Embedded Metric Format (EMF) log line
     * per Gemini call so CloudWatch derives metrics without us paying
     * {@code PutMetricData} cost or wiring extra IAM. The line is single-line
     * JSON that includes a {@code _aws} envelope describing the namespace,
     * dimensions, and metric names; CloudWatch Logs picks it up automatically
     * and surfaces the metrics under {@code CatalystFlow/AI}.
     *
     * <h2>Two metric definitions per call</h2>
     * The emission declares TWO entries in the {@code CloudWatchMetrics}
     * array — both share the same top-level metric values, but they
     * generate two different CloudWatch time series:
     * <ul>
     *   <li><b>Per-task dimensions</b> {@code [taskType, stage]} — drives
     *       dashboards that slice cache hit-rate per task. One series per
     *       (taskType, stage) pair.</li>
     *   <li><b>Stage-only dimensions</b> {@code [stage]} — drives the
     *       {@code CacheHitRateLowAlarm} in {@code template.yaml}, which is
     *       org-wide per stage. The alarm uses Metric Math
     *       {@code SUM(CacheHit) / SUM(Cacheable)} on this series so
     *       non-cacheable calls (Cacheable=0) don't drag the rate down —
     *       only cacheable calls' hit ratio matters.</li>
     * </ul>
     *
     * <p>Pre-fix the alarm watched a dimensionless metric while emissions
     * were all dimensioned — CloudWatch reported insufficient data forever.
     * Reviewer caught it.
     *
     * <h2>Metrics emitted (all unit Count)</h2>
     * {@code CacheHit} (0|1), {@code Cacheable} (0|1), {@code TokenIn},
     * {@code TokenOut}, {@code CachedTokens}.
     *
     * <p>Null token values are emitted as 0 — the honest answer for
     * non-cached calls (no cached tokens were served) and for upstream-broken
     * calls (we didn't get a usage block). Treating null-as-0 keeps the
     * CloudWatch Sum / Average semantics sane; treating null-as-no-datapoint
     * would hide failed calls from the cache-rate denominator.
     *
     * <h2>Dimension naming</h2>
     * {@code taskType} and {@code stage} (lowercase) per plan AC-11.2. The
     * {@code template.yaml} alarm Dimensions definitions must match these
     * names exactly — CloudWatch dimension names are case-sensitive.
     *
     * <h2>Canonical task-type value</h2>
     * The {@code taskType} dimension VALUE is the canonical
     * {@code TaskMetadata.TASK_*} string ({@code "MANAGER_GOLDEN"},
     * {@code "ANALYZE"}, etc.) — NOT the kebab-case
     * {@link GeminiRequest#taskName} ({@code "manager-golden"},
     * {@code "analyze"}). Two reasons:
     * <ul>
     *   <li>It matches the {@code AiJobs.taskType} column, so CloudWatch
     *       dimensions and DDB rows can be joined in dashboards.</li>
     *   <li>{@link CacheRequest#taskType} already uses the canonical form;
     *       when present we read it directly. When absent (non-cacheable
     *       calls) {@link TaskMetadata#canonicalTaskType} maps the kebab
     *       request name to the canonical constant.</li>
     * </ul>
     *
     * <h2>Why raw stdout (not LOG.info)</h2>
     * JBoss Logger prepends a timestamp + level + category to every line;
     * CloudWatch's EMF parser then sees non-JSON-leading bytes and rejects
     * the line, silently dropping the metric. Writing directly to a
     * {@link java.io.PrintStream} (default {@link System#out}) gives Lambda's
     * CloudWatch Logs integration a bare JSON event — EMF parses cleanly.
     *
     * <p>Never throws. EMF emission failure is caught and logged at WARN —
     * observability code must not break the user-facing call (consistent
     * with the safeCacheKeyForLog contract on the LOG.infof above).
     */
    private void emitCacheMetric(String taskName, CacheRequest cacheRequest,
                                 boolean cacheHit, boolean cacheable,
                                 Integer tokenIn, Integer tokenOut, Integer cachedTokens) {
        try {
            // Canonical task-type value — see Javadoc above. Prefer the
            // CacheRequest's value (already canonical) when present; otherwise
            // map the kebab request name via TaskMetadata.canonicalTaskType.
            String canonicalTaskType = cacheRequest != null
                    ? cacheRequest.taskType()
                    : TaskMetadata.canonicalTaskType(taskName);
            Map<String, Object> emf = new LinkedHashMap<>();
            Map<String, Object> aws = new LinkedHashMap<>();
            aws.put("Timestamp", System.currentTimeMillis());
            // Two metric definitions — see Javadoc above. Per-task gives
            // dashboard granularity; stage-only is what the alarm watches.
            List<Map<String, Object>> metricDefs = List.of(
                    Map.of(
                            "Namespace", "CatalystFlow/AI",
                            "Dimensions", List.of(List.of("taskType", "stage")),
                            "Metrics", List.of(
                                    Map.of("Name", "CacheHit", "Unit", "Count"),
                                    Map.of("Name", "Cacheable", "Unit", "Count"),
                                    Map.of("Name", "TokenIn", "Unit", "Count"),
                                    Map.of("Name", "TokenOut", "Unit", "Count"),
                                    Map.of("Name", "CachedTokens", "Unit", "Count")
                            )
                    ),
                    Map.of(
                            "Namespace", "CatalystFlow/AI",
                            "Dimensions", List.of(List.of("stage")),
                            "Metrics", List.of(
                                    Map.of("Name", "CacheHit", "Unit", "Count"),
                                    Map.of("Name", "Cacheable", "Unit", "Count")
                            )
                    )
            );
            aws.put("CloudWatchMetrics", metricDefs);
            emf.put("_aws", aws);
            // Dimension values (lowercase per AC-11.2). taskType is the
            // canonical TaskMetadata.TASK_* value, NOT the kebab request name.
            emf.put("taskType", canonicalTaskType);
            emf.put("stage", stage);
            // Metric values (shared across both metric definitions).
            emf.put("CacheHit", cacheHit ? 1 : 0);
            emf.put("Cacheable", cacheable ? 1 : 0);
            emf.put("TokenIn", tokenIn == null ? 0 : tokenIn);
            emf.put("TokenOut", tokenOut == null ? 0 : tokenOut);
            emf.put("CachedTokens", cachedTokens == null ? 0 : cachedTokens);
            // Single-line JSON to raw stdout — CW Logs sees a bare JSON event.
            emfSink.println(mapper.writeValueAsString(emf));
        } catch (Exception e) {
            LOG.warnf(e, "Gemini EMF metric emit failed for task=%s — dropping (observability "
                    + "must not break the user-facing call)", taskName);
        }
    }

    // ── Shared helpers (formerly duplicated across both services) ──────────────

    /**
     * Parse a Retry-After header (RFC 9110) from a Gemini response.
     *
     * <p>Gemini emits Retry-After as a decimal integer in seconds when it
     * rate-limits. We also tolerate the HTTP-date form per the spec, falling
     * back to empty on anything else.
     *
     * @return Retry-After value converted to milliseconds, or empty if no
     *         header or parsing fails.
     */
    static OptionalLong parseRetryAfterMs(HttpResponse<?> response) {
        Optional<String> headerVal = response.headers().firstValue("retry-after");
        if (headerVal.isEmpty()) return OptionalLong.empty();
        String v = headerVal.get().trim();
        // Form 1: integer seconds (the Gemini case).
        try {
            long seconds = Long.parseLong(v);
            if (seconds < 0) return OptionalLong.empty();
            return OptionalLong.of(seconds * 1000L);
        } catch (NumberFormatException ignored) {
            // fall through to HTTP-date attempt
        }
        // Form 2: HTTP-date (rare in practice for Gemini, but spec-compliant).
        try {
            ZonedDateTime when = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
            long deltaMs = java.time.Duration.between(ZonedDateTime.now(), when).toMillis();
            return deltaMs > 0 ? OptionalLong.of(deltaMs) : OptionalLong.of(0L);
        } catch (Exception ignored) {
            return OptionalLong.empty();
        }
    }

    /**
     * Best-effort redaction for log previews — strips obvious UUIDs, emails,
     * and API keys before truncating to 500 chars. Only active when the
     * {@code ai-review.log-llm-previews} flag is true.
     */
    static String redactedPreview(String text) {
        if (text == null || text.isBlank()) return "";
        String redacted = text
                .replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "[uuid]")
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[email]")
                .replaceAll("AIza[0-9A-Za-z_-]+", "[api-key]");
        return redacted.length() <= 500 ? redacted : redacted.substring(0, 500) + "...";
    }

    /**
     * Retryable/deferrable upstream server errors. 503 ("model overloaded /
     * UNAVAILABLE") is the common transient case; 500 (INTERNAL) and 502
     * (BAD_GATEWAY) are also typically transient. Deliberately excludes 504
     * (mapped to TIMEOUT — our 500 s budget already elapsed) and non-transient
     * 5xx like 501/505, which stay terminal.
     */
    static boolean isTransientUpstream(int code) {
        return code == 500 || code == 502 || code == 503;
    }

    static void sleepQuiet(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

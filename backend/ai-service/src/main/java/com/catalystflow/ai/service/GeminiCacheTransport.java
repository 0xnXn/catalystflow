package com.catalystflow.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cache-side Gemini HTTP transport (CC-2 follow-up). Pair-bean to
 * {@link GeminiClient}.
 *
 * <h2>Why a separate bean</h2>
 * AC-8.6 specifies that "All Gemini API calls go through GeminiClient.callCachedContents
 * (a private helper on GeminiClient)" — i.e., the cache HTTP should not be on
 * the user-facing client surface. Splitting into a separate bean does that
 * cleanly AND prevents the CDI cycle that CC-3 would otherwise create:
 *
 * <pre>
 *   GeminiClient.call       →  GeminiCacheService.resolveOrCreate  (CC-3)
 *   GeminiCacheService      →  GeminiClient.callCachedContents     (would close cycle)
 * </pre>
 *
 * With the transport extracted, CC-3 wiring becomes:
 *
 * <pre>
 *   GeminiClient            →  GeminiCacheService                   (new in CC-3)
 *   GeminiCacheService      →  GeminiCacheTransport                 (this bean)
 *   No cycle.
 * </pre>
 *
 * <h2>Endpoints</h2>
 * Two Gemini API endpoints live here:
 * <ul>
 *   <li>{@code POST /v1beta/cachedContents} — create a new context cache.</li>
 *   <li>{@code POST /v1beta/models/{model}:countTokens} — preflight token
 *       count, used by {@link GeminiCacheService} to skip cache creation
 *       when the prefix is below the 1500-token safety margin
 *       (Gemini's min cacheable size is 1024 — we leave headroom for
 *       per-model variance).</li>
 * </ul>
 *
 * Both methods are degrade-gracefully: return {@code null} on any failure
 * (network IO, timeout, 4xx, 5xx, parse drift). Never throw. The cache
 * layer is non-essential to the user-facing flow — when it fails we want
 * to keep serving uncached, not break the AI call.
 */
@ApplicationScoped
public class GeminiCacheTransport {

    private static final Logger LOG = Logger.getLogger(GeminiCacheTransport.class);

    /**
     * Per-attempt HTTP timeout for cache-side calls. Shorter than
     * {@code GeminiClient.GEMINI_HTTP_TIMEOUT} (500s for generateContent)
     * because (a) cachedContents creation is bounded — Gemini's docs cap
     * processing time well under a minute even on the model's max cacheable
     * prefix; (b) cache-side calls are auxiliary — we don't want them
     * eating the AiWorker Lambda's 540s budget at the expense of the
     * downstream generateContent call.
     */
    private static final Duration CACHE_HTTP_TIMEOUT = Duration.ofSeconds(30);

    String geminiApiKey = "";

    @Inject
    void configureGeminiApiKey(@ConfigProperty(name = "gemini.api-key") java.util.Optional<String> configuredKey) {
        geminiApiKey = configuredKey.orElse("");
    }

    @Inject
    ObjectMapper mapper;

    /**
     * Package-private (not private) so {@code GeminiCacheTransportTest} can
     * swap in a Mockito mock — {@link HttpClient} is not a CDI bean. Mirror
     * of the same pattern used by {@link GeminiClient#httpClient}.
     */
    HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Result of {@link #callCachedContents}: the Gemini cache resource name
     * ({@code cachedContents/<id>}) plus the {@code cachedTokens} count when
     * the response includes it. {@code cachedTokens} is nullable because the
     * Gemini API is not strictly consistent about emitting it on cache-create
     * — the field is for CC-5's cost-attribution dashboard.
     */
    @RegisterForReflection
    public record CacheCreateResult(String cacheName, Integer cachedTokens) {
    }

    // ── /v1beta/cachedContents ────────────────────────────────────────────────

    /**
     * POST {@code /v1beta/cachedContents} to create a Gemini context cache.
     * Returns the cache resource name on success or {@code null} on any
     * failure.
     *
     * <p>The system instruction and the extra prefix are sent SEPARATELY so
     * Gemini stores them with correct role semantics:
     * <ul>
     *   <li>{@code systemInstruction} carries {@code systemPrompt} —
     *       Gemini will read this as a true system message when the cache
     *       is used later via {@code cachedContent: <name>}.</li>
     *   <li>{@code contents[0]} (role=user) carries {@code extraPrefix} —
     *       the static-per-(discipline, seniority) preamble (criteria,
     *       style, task rules) the user-facing call wants Gemini to read
     *       BEFORE its per-call message.</li>
     * </ul>
     *
     * <p>Pre-fix this method concatenated {@code systemPrompt + extraPrefix}
     * and put the whole blob in a single user-role part — Gemini then read
     * the system prompt as user content, losing the role distinction the
     * downstream {@code generateContent} call relies on.
     *
     * @param modelName        Gemini model id (with or without {@code "models/"} prefix)
     * @param systemPrompt     System-level instructions for the cache
     * @param extraPrefix      Cacheable user-role preamble; may be {@code null} or empty
     * @param ttlSeconds       How long Gemini should keep the cache alive
     */
    public CacheCreateResult callCachedContents(String modelName,
                                                String systemPrompt,
                                                String extraPrefix,
                                                long ttlSeconds) {
        String normalisedModel = normaliseModel(modelName);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", normalisedModel);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            requestBody.put("systemInstruction",
                    Map.of("parts", List.of(Map.of("text", systemPrompt))));
        }
        if (extraPrefix != null && !extraPrefix.isEmpty()) {
            requestBody.put("contents", List.of(Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", extraPrefix)))));
        }
        requestBody.put("ttl", ttlSeconds + "s");

        String body = serialise(requestBody, "cachedContents request");
        if (body == null) return null;

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/cachedContents"))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", geminiApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(CACHE_HTTP_TIMEOUT)
                .build();

        HttpResponse<String> response = send(httpRequest, "cachedContents");
        if (response == null) return null;

        int code = response.statusCode();
        if (code != 200) {
            // Most common 4xx: min_total_token_count complaint when the content
            // is below Gemini's minimum cacheable size. countTokens preflight in
            // GeminiCacheService normally prevents that path, but the threshold
            // is conservative and Gemini's actual minimum can vary per model —
            // belt-and-braces.
            LOG.warnf("Gemini cachedContents create returned %d: %s — degrading to uncached",
                    code, truncate(response.body()));
            return null;
        }

        try {
            JsonNode root = mapper.readTree(response.body());
            String name = root.path("name").asText("");
            if (name.isBlank()) {
                LOG.warnf("Gemini cachedContents 200 but no 'name' in response: %s",
                        truncate(response.body()));
                return null;
            }
            Integer cachedTokens = null;
            JsonNode usage = root.path("usageMetadata");
            if (!usage.isMissingNode()) {
                JsonNode totalTokens = usage.path("totalTokenCount");
                if (!totalTokens.isMissingNode()) {
                    cachedTokens = totalTokens.asInt(0);
                }
            }
            LOG.infof("Gemini cachedContents created: name=%s ttl=%ds cachedTokens=%s",
                    name, ttlSeconds, cachedTokens == null ? "unknown" : cachedTokens);
            return new CacheCreateResult(name, cachedTokens);
        } catch (Exception e) {
            LOG.warnf(e, "Gemini cachedContents response parse failed — degrading to uncached");
            return null;
        }
    }

    // ── /v1beta/models/{model}:countTokens ────────────────────────────────────

    /**
     * POST {@code /v1beta/models/{model}:countTokens} to preflight the token
     * count for content we're about to cache. Returns the total token count
     * on success or {@code null} on any failure.
     *
     * <p>Caller ({@link GeminiCacheService}) compares the result against a
     * safety threshold (1500 tokens) — below that we skip
     * {@link #callCachedContents} entirely, saving a guaranteed-to-fail
     * round-trip and avoiding Gemini's "min_total_token_count" billing
     * surprise.
     */
    public Integer countTokens(String modelName, String content) {
        String normalisedModel = normaliseModel(modelName);

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", content)))));

        String body = serialise(requestBody, "countTokens request");
        if (body == null) return null;

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/"
                        + normalisedModel + ":countTokens"))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", geminiApiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(CACHE_HTTP_TIMEOUT)
                .build();

        HttpResponse<String> response = send(httpRequest, "countTokens");
        if (response == null) return null;

        if (response.statusCode() != 200) {
            LOG.warnf("Gemini countTokens returned %d: %s — degrading to uncached",
                    response.statusCode(), truncate(response.body()));
            return null;
        }

        try {
            JsonNode root = mapper.readTree(response.body());
            JsonNode totalTokens = root.path("totalTokens");
            if (totalTokens.isMissingNode()) {
                LOG.warnf("Gemini countTokens 200 but no 'totalTokens' field: %s",
                        truncate(response.body()));
                return null;
            }
            return totalTokens.asInt();
        } catch (Exception e) {
            LOG.warnf(e, "Gemini countTokens response parse failed");
            return null;
        }
    }

    // ── /v1beta/{cacheName} DELETE ───────────────────────────────────────────

    /**
     * CC-6 admin invalidation — delete a Gemini cachedContents resource by
     * its resource name (e.g. {@code cachedContents/xyz789}). Best-effort:
     * any failure (404 because the TTL already expired upstream, 5xx, IO,
     * timeout, parse) is logged and swallowed; the method returns silently.
     * Callers should NOT 500 the user-facing admin call on Gemini DELETE
     * failure — the DDB row will be removed regardless and the next
     * resolveOrCreate forces a fresh create.
     *
     * <p>404 in particular is a normal outcome: Gemini's own TTL may have
     * already reaped the resource between the DDB write and our DELETE.
     * Treating 404 as success keeps admin DELETE idempotent.
     *
     * <p>This is the ONLY mutation method on the cache-side transport that
     * intentionally takes a {@code cacheName} (the resource ID, not a
     * content hash) — matches the Gemini API's
     * {@code DELETE /v1beta/{cacheName}} endpoint shape.
     */
    public void deleteCachedContent(String cacheName) {
        if (cacheName == null || cacheName.isBlank()) {
            LOG.warnf("Gemini deleteCachedContent called with blank cacheName — skipping");
            return;
        }
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/" + cacheName))
                .header("x-goog-api-key", geminiApiKey)
                .DELETE()
                .timeout(CACHE_HTTP_TIMEOUT)
                .build();

        HttpResponse<String> response = send(httpRequest, "deleteCachedContents");
        if (response == null) {
            // IO / timeout / interrupted — already logged in send(). Swallow.
            return;
        }
        int code = response.statusCode();
        if (code == 200 || code == 204) {
            LOG.infof("Gemini cachedContents deleted: name=%s", cacheName);
            return;
        }
        if (code == 404) {
            // Already gone — TTL likely fired before us. Idempotent admin
            // DELETE: treat as success.
            LOG.infof("Gemini cachedContents already absent: name=%s (404 — TTL or prior delete)",
                    cacheName);
            return;
        }
        LOG.warnf("Gemini cachedContents delete returned %d: %s — DDB row will still be removed",
                code, truncate(response.body()));
    }

    // ── shared helpers ───────────────────────────────────────────────────────

    private static String normaliseModel(String modelName) {
        return modelName.startsWith("models/") ? modelName : "models/" + modelName;
    }

    private String serialise(Object body, String label) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            LOG.errorf(e, "Gemini cache transport: %s build failed", label);
            return null;
        }
    }

    private HttpResponse<String> send(HttpRequest request, String label) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            LOG.warnf(e, "Gemini %s call timed out after %s — degrading to uncached",
                    label, CACHE_HTTP_TIMEOUT);
            return null;
        } catch (java.io.IOException e) {
            LOG.warnf(e, "Gemini %s IO failure (%s) — degrading to uncached",
                    label, e.getClass().getSimpleName());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}

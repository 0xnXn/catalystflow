package com.catalystflow.ai.service;

import com.catalystflow.ai.model.AiReviewQuestionAnswer;
import com.catalystflow.ai.model.AiReviewQuestionFeedback;
import com.catalystflow.ai.model.AiReviewRequest;
import com.catalystflow.ai.model.AiReviewResponse;
import com.catalystflow.ai.schema.ResponseSchemas;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RS-5 — verifies the {@code ANALYZE} path uses Gemini's
 * {@code responseSchema} and that {@code parseResponse} no longer carries
 * markdown-strip / brace-find heuristics (deleted per plan AC-5.3).
 *
 * <p>Pure unit test — mirrors {@code AiReviewServiceInsightsSchemaTest}.
 * Wires the package-private fields directly and invokes
 * {@code @PostConstruct init()} via reflection to load criteriaTree and
 * styleReferences from the classpath.
 */
class AiReviewServiceAnalyzeSchemaTest {

    private AiReviewService svc;
    private GeminiClient geminiClient;

    @BeforeEach
    void setup() throws Exception {
        geminiClient = mock(GeminiClient.class);
        svc = new AiReviewService();
        setField(svc, "mockMode", false);
        // Disable the local fallback so failures surface — otherwise the
        // BAD_GATEWAY catch silently returns buildProviderFallbackResponse
        // and the test can't observe the exception.
        setField(svc, "providerFallbackEnabled", false);
        setField(svc, "geminiClient", geminiClient);
        setField(svc, "modelResolver", ModelResolver.allTasksUseModel("gemini-test"));
        setField(svc, "jobsQueueUrl", Optional.empty());
        // CC-4c: default OFF — production default. Specific tests opt in.
        setField(svc, "analyzeCacheEnabled", false);
        setField(svc, "insightsCacheEnabled", false);
        invoke(svc, "init");
    }

    // ── AC-5.2: responseSchema is actually sent ──────────────────────────────

    @Test
    void analyzeWithUsage_passesResponseSchemaToGemini() {
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validAnalyzeJson()));

        svc.analyzeWithUsage(analyzeRequest());

        ArgumentCaptor<GeminiRequest> reqCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(1)).call(reqCaptor.capture());
        GeminiRequest captured = reqCaptor.getValue();

        assertNotNull(captured.responseSchema(),
                "RS-5: responseSchema must be populated on the GeminiRequest");
        assertEquals("analyze", captured.taskName());
        assertEquals(ResponseSchemas.schemaForAnalyze(), captured.responseSchema(),
                "Schema must come from ResponseSchemas.schemaForAnalyze() — single source of truth");
    }

    // ── Success path: response parses, normalizeResponse applies ─────────────

    @Test
    void analyzeWithUsage_validResponse_normalizesAndReturns() {
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(new GeminiResult(validAnalyzeJson(), 500, 200, "STOP", "gemini-test", false, null));

        AiReviewService.AnalyzeResult result = svc.analyzeWithUsage(analyzeRequest());

        assertNotNull(result.response());
        assertEquals(1, result.response().getSuggestions().size());
        AiReviewQuestionFeedback item = result.response().getSuggestions().get(0);
        assertEquals("q1", item.getQuestionId());
        assertEquals(AiReviewQuestionFeedback.VerdictEnum.STRONG, item.getVerdict());
        assertEquals(Integer.valueOf(500), result.tokenIn());
        assertEquals(Integer.valueOf(200), result.tokenOut());
    }

    // ── AC-5.3: parseResponse no longer strips markdown fences ───────────────

    @Test
    void parseResponse_markdownFencedJson_isRejectedNotStripped() {
        // Pre-RS-5 parseResponse stripped ```json ... ``` fences silently. Plan
        // AC-5.3 says the markdown-strip + brace-find heuristics are gone —
        // schema-enforced output is raw JSON. If Gemini drifts back to fences,
        // it's a real signal: surface as MALFORMED rather than silently strip.
        String fenced = "```json\n" + validAnalyzeJson() + "\n```";
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(fenced));

        WebApplicationException ex = assertThrows(
                WebApplicationException.class,
                () -> svc.analyzeWithUsage(analyzeRequest()),
                "Post-RS-5 markdown-fenced output must FAIL parsing — no silent stripping");

        assertEquals(502, ex.getResponse().getStatus());
        assertTrue(ex.getMessage().contains("Invalid AI response"),
                "Message uses the ANALYZE-specific marker: " + ex.getMessage());
        // RS-5 follow-up: parse failures after a successful Gemini call must
        // carry telemetry so the FAILED row reflects the real cost spent.
        assertInstanceOf(GeminiTelemetryException.class, ex,
                "Parse failures must be wrapped as GeminiTelemetryException");
        GeminiTelemetryException gte = (GeminiTelemetryException) ex;
        assertEquals(Integer.valueOf(500), gte.tokenIn,
                "tokenIn from successful Gemini call must propagate to FAILED row");
        assertEquals(Integer.valueOf(200), gte.tokenOut);
        assertEquals("gemini-test", gte.modelUsed);
    }

    @Test
    void parseResponse_leadingPrelude_isRejectedNotBraceFound() {
        // Pre-RS-5 parseResponse hunted for the first '{' to skip any
        // prelude like "Here's the response:\n{...}". Plan AC-5.3 deletes
        // that heuristic — schema-enforced raw JSON has no prelude. Drift
        // surfaces as MALFORMED.
        String prelude = "Here is the response:\n" + validAnalyzeJson();
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(prelude));

        WebApplicationException ex = assertThrows(
                WebApplicationException.class,
                () -> svc.analyzeWithUsage(analyzeRequest()));

        assertEquals(502, ex.getResponse().getStatus());
    }

    @Test
    void parseResponse_unparseableJson_throwsBadGateway() {
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult("not json at all"));

        WebApplicationException ex = assertThrows(
                WebApplicationException.class,
                () -> svc.analyzeWithUsage(analyzeRequest()));

        assertEquals(502, ex.getResponse().getStatus());
    }

    // ── Provider-fallback path: when enabled, failures return local stub ─────

    @Test
    void analyzeWithUsage_unparseableJson_withFallbackEnabled_returnsLocalStub() throws Exception {
        // AC-5.4: the provider-fallback path is unchanged — when
        // providerFallbackEnabled=true, BAD_GATEWAY failures fall through to
        // buildProviderFallbackResponse. This is a UX preserve so the user
        // always sees SOMETHING (canned suggestions) even when Gemini is
        // unavailable. Regression guard against the fallback being lost.
        setField(svc, "providerFallbackEnabled", true);
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult("not json at all"));

        AiReviewService.AnalyzeResult result = svc.analyzeWithUsage(analyzeRequest());

        assertNotNull(result.response(),
                "Provider fallback must keep returning a usable response object");
        assertNotNull(result.response().getSuggestions());
        assertTrue(result.response().getSuggestions().size() >= 1,
                "Fallback must include at least one suggestion: " + result.response().getSuggestions());

        // RS-5 follow-up #2: even on the fallback (COMPLETED) path, the
        // Gemini telemetry recovered from the GeminiTelemetryException must
        // propagate onto the AnalyzeResult so the AiJobs row carries cost
        // attribution. Pre-fix the fallback returned (response, null, null)
        // and the 200 OK Gemini spend was invisible in dashboards.
        assertEquals(Integer.valueOf(500), result.tokenIn(),
                "Fallback must preserve recovered tokenIn — Gemini call DID cost tokens");
        assertEquals(Integer.valueOf(200), result.tokenOut(),
                "Fallback must preserve recovered tokenOut");
    }

    @Test
    void analyzeWithUsage_networkFailure_withFallbackEnabled_returnsLocalStubWithNullTelemetry() throws Exception {
        // Symmetric guard: when the WebApplicationException comes from a
        // network/upstream failure (NOT GeminiTelemetryException), there are
        // no tokens to recover — telemetry stays null. This pins the
        // distinction between "parse failed after 200" and "Gemini never
        // returned a usable result".
        setField(svc, "providerFallbackEnabled", true);
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenThrow(new WebApplicationException("AI service unavailable", 503));

        AiReviewService.AnalyzeResult result = svc.analyzeWithUsage(analyzeRequest());

        assertNotNull(result.response(), "Fallback still fires for network failures");
        assertEquals(null, result.tokenIn(),
                "Network failure had no Gemini call → tokenIn must stay null");
        assertEquals(null, result.tokenOut());
    }

    // ── CC-4c: per-task cache enablement (ANALYZE) ───────────────────────────

    @Test
    void analyzeWithUsage_cacheFlagOff_sendsNoCacheRequest_promptShapeUnchanged() {
        // Default behaviour (gemini-cache.enabled.analyze=false). GeminiRequest
        // has cacheRequest=null; userPrompt contains the FULL combined prompt
        // (cacheable prefix + dynamic part) — same shape as pre-CC-4c.
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validAnalyzeJson()));

        svc.analyzeWithUsage(analyzeRequest());

        ArgumentCaptor<GeminiRequest> reqCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(1)).call(reqCaptor.capture());
        GeminiRequest captured = reqCaptor.getValue();

        org.junit.jupiter.api.Assertions.assertNull(captured.cacheRequest(),
                "Flag OFF: cacheRequest must be null so GeminiClient skips the cache path");
        org.junit.jupiter.api.Assertions.assertNull(captured.uncachedPrompt(),
                "Flag OFF: uncachedPrompt must be null when there's no cacheRequest");
        assertTrue(captured.userPrompt().contains("PERFORMANCE CRITERIA"),
                "Flag OFF: userPrompt must include the cacheable-prefix portion");
        assertTrue(captured.userPrompt().contains("EMPLOYEE DRAFT"),
                "Flag OFF: userPrompt must include the dynamic portion");
    }

    @Test
    void analyzeWithUsage_cacheFlagOn_attachesCacheRequest_correctShape() throws Exception {
        // AC-10.1: when the flag is on, analyzeWithUsage must build a
        // CacheRequest keyed on (ANALYZE, V, discipline, seniority) with the
        // criteria + style block as extraPrefix, and pass dynamic-only as
        // userPrompt with the full prompt as uncachedPrompt for fallback.
        setField(svc, "analyzeCacheEnabled", true);
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validAnalyzeJson()));

        svc.analyzeWithUsage(analyzeRequest());

        ArgumentCaptor<GeminiRequest> reqCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(1)).call(reqCaptor.capture());
        GeminiRequest captured = reqCaptor.getValue();

        assertNotNull(captured.cacheRequest(),
                "Flag ON: cacheRequest must be populated");
        CacheRequest cr = captured.cacheRequest();
        assertEquals(TaskMetadata.TASK_ANALYZE, cr.taskType());
        assertEquals(TaskMetadata.PROMPT_VERSION_ANALYZE, cr.promptVersion());
        assertEquals("Software & DevOps Engineering", cr.discipline(),
                "discipline keys the cache row — different discipline → different cache");
        assertEquals("Engineer", cr.seniority());
        assertEquals(3600L, cr.ttlSeconds(),
                "§D5: ANALYZE uses 1h TTL (repeats during an employee drafting session)");
        assertEquals("gemini-test", cr.model());
        assertTrue(cr.extraPrefix().contains("PERFORMANCE CRITERIA"),
                "extraPrefix must include the criteria block: "
                        + cr.extraPrefix().substring(0, Math.min(200, cr.extraPrefix().length())));
        assertTrue(cr.extraPrefix().contains("QUALITY SIGNALS"),
                "extraPrefix must include the QUALITY SIGNALS block");
        // CC-4b round-2 pattern: assert systemPrompt cross-equality — pins
        // that the cache row holds the same bytes the request sends.
        assertEquals(captured.systemPrompt(), cr.systemPrompt(),
                "CacheRequest.systemPrompt must equal the request's systemPrompt — "
                        + "otherwise cache row holds different bytes than Gemini receives");
        // userPrompt is dynamic-only; PERFORMANCE CRITERIA (in prefix) must NOT
        // leak into userPrompt when caching is on.
        assertTrue(captured.userPrompt().contains("EMPLOYEE DRAFT"),
                "Flag ON: userPrompt has the dynamic portion");
        org.junit.jupiter.api.Assertions.assertFalse(
                captured.userPrompt().contains("PERFORMANCE CRITERIA"),
                "Flag ON: userPrompt must NOT include the cached prefix portion");
    }

    @Test
    void analyzeWithUsage_cacheFlagOn_uncachedPromptEqualsCacheOffShape_byteIdentity() throws Exception {
        // Regression guard: uncachedPrompt MUST equal what cache-OFF would
        // have sent. Otherwise the cache-miss fallback would deliver a
        // different prompt to Gemini than the cache-OFF path. Pin byte
        // identity so any future edit to either path that breaks this
        // contract fails loudly.
        AiReviewRequest req = analyzeRequest();
        java.util.List<String> tech = (java.util.List<String>) invokeReturning(svc, "stackCriteria",
                req.getDiscipline(), req.getSeniority(), "technology");
        java.util.List<String> consulting = (java.util.List<String>) invokeReturning(svc, "stackCriteria",
                req.getDiscipline(), req.getSeniority(), "consulting");
        String style = (String) invokeReturning(svc, "loadStyleReference", req.getDiscipline());
        String draft = (String) invokeReturning(svc, "joinQuestions", req.getQuestions());
        String expectedFullPrompt = svc.buildAnalyzeCacheablePrefix(req.getDiscipline(),
                       req.getSeniority(), tech, consulting, style)
                + svc.buildAnalyzeDynamicPart(req, draft);

        setField(svc, "analyzeCacheEnabled", true);
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validAnalyzeJson()));
        svc.analyzeWithUsage(req);

        ArgumentCaptor<GeminiRequest> reqCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(1)).call(reqCaptor.capture());
        GeminiRequest captured = reqCaptor.getValue();

        assertEquals(expectedFullPrompt, captured.uncachedPrompt(),
                "Cache-ON uncachedPrompt must equal cacheable-prefix + dynamic-part — "
                        + "proves the cache-miss fallback delivers the same bytes as cache-OFF");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static GeminiResult geminiResult(String text) {
        return new GeminiResult(text, 500, 200, "STOP", "gemini-test", false, null);
    }

    /** Reflection helper for invoking private methods that return a value. */
    private static Object invokeReturning(Object target, String method, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? Object.class : args[i].getClass();
        }
        // Walk supers if needed — but AiReviewService is concrete so direct lookup works.
        // Special-case List arg: passed-in class is ArrayList/ImmutableList, method declares List.
        var m = java.util.Arrays.stream(target.getClass().getDeclaredMethods())
                .filter(mm -> mm.getName().equals(method) && mm.getParameterCount() == args.length)
                .findFirst().orElseThrow(() -> new IllegalStateException("no method " + method));
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    /** Schema-valid ANALYZE JSON: one STRONG question with a single suggestion. */
    private static String validAnalyzeJson() {
        return "{"
                + "\"suggestions\":[{"
                + "  \"questionId\":\"q1\","
                + "  \"questionText\":\"Describe your technical contributions.\","
                + "  \"verdict\":\"STRONG\","
                + "  \"suggestions\":[\"Strong stakeholder example with clear outcome.\"]"
                + "}]"
                + "}";
    }

    private static AiReviewRequest analyzeRequest() {
        AiReviewRequest req = new AiReviewRequest();
        req.setDiscipline("Software & DevOps Engineering");
        req.setSeniority("Engineer");
        AiReviewQuestionAnswer qa = new AiReviewQuestionAnswer();
        qa.setQuestionId("q1");
        qa.setQuestionText("Describe your technical contributions.");
        qa.setAnswer("Shipped the FX nightly close from 4 hours to 35 minutes.");
        req.setQuestions(List.of(qa));
        return req;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void invoke(Object target, String method) throws Exception {
        var m = target.getClass().getDeclaredMethod(method);
        m.setAccessible(true);
        m.invoke(target);
    }
}

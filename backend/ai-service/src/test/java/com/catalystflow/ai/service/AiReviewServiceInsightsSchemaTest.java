package com.catalystflow.ai.service;

import com.catalystflow.ai.model.AiReviewInsightsRequest;
import com.catalystflow.ai.model.AiReviewQuestionAnswer;
import com.catalystflow.ai.schema.ResponseSchemas;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * RS-5 — verifies the {@code INSIGHTS} path uses Gemini's
 * {@code responseSchema} and that the existing safety-net layers
 * ({@code coerceStringField} + {@code validateInsightsResponse}) still
 * function as the defensive last line.
 *
 * <p>Pure unit test — instantiates {@link AiReviewService} directly and
 * wires package-private fields by hand. Manually invokes the
 * {@code @PostConstruct init()} so {@code criteriaTree} /
 * {@code styleReferences} are loaded from the classpath the same way
 * Quarkus would have done.
 *
 * <p>Mocks {@link GeminiClient} so the test controls the response shape
 * without making any real HTTP call.
 */
class AiReviewServiceInsightsSchemaTest {

    private AiReviewService svc;
    private GeminiClient geminiClient;

    @BeforeEach
    void setup() throws Exception {
        geminiClient = mock(GeminiClient.class);
        svc = new AiReviewService();
        // mockMode default is false in production; the test-resources
        // application.properties sets ai-review.mock=true which we sidestep
        // by writing the field directly.
        setField(svc, "mockMode", false);
        setField(svc, "providerFallbackEnabled", false); // so failures don't fall through to the local fallback
        setField(svc, "geminiClient", geminiClient);
        setField(svc, "modelResolver", ModelResolver.allTasksUseModel("gemini-test"));
        setField(svc, "jobsQueueUrl", Optional.empty());
        // CC-4c: default OFF — production default. Specific tests opt in.
        setField(svc, "analyzeCacheEnabled", false);
        setField(svc, "insightsCacheEnabled", false);
        // Load criteriaTree + styleReferences via the production @PostConstruct path.
        invoke(svc, "init");
    }

    // ── AC-5.2: responseSchema is actually sent ──────────────────────────────

    @Test
    void generateInsights_passesResponseSchemaToGemini() {
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validInsightsJson()));

        svc.generateInsights(insightsRequest());

        ArgumentCaptor<GeminiRequest> reqCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(1)).call(reqCaptor.capture());
        GeminiRequest captured = reqCaptor.getValue();

        assertNotNull(captured.responseSchema(),
                "RS-5: responseSchema must be populated on the GeminiRequest");
        assertEquals("insights", captured.taskName());
        assertEquals(ResponseSchemas.schemaForInsights(), captured.responseSchema(),
                "Schema must come from ResponseSchemas.schemaForInsights() — single source of truth");
    }

    // ── Success path: response parsed + telemetry threaded ───────────────────

    @Test
    void generateInsights_validResponse_returnsParsedFields() {
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(new GeminiResult(validInsightsJson(), 1200, 800, "STOP", "gemini-test", false, null));

        AiReviewService.InsightsResult result = svc.generateInsightsWithUsage(insightsRequest(), null);

        assertNotNull(result.response());
        assertTrue(result.response().getSummary().contains("Core impact"),
                "summary must round-trip");
        assertTrue(result.response().getGoldenMirror().contains("TECHNOLOGY"),
                "goldenMirror must round-trip");
        assertEquals(Integer.valueOf(1200), result.tokenIn());
        assertEquals(Integer.valueOf(800), result.tokenOut());
    }

    // ── RS-6 AC-6.3: coerceStringField is GONE — array shape now FAILS ───────

    @Test
    void parseInsightsResponse_arrayShapeSummary_rejectedAsMalformed() {
        // Pre-RS-6 the coerceStringField safety net silently mutated array
        // -> newline-joined string. Per plan AC-6.3 that safety net is
        // deleted. Schema enforces type:"string" server-side; if Gemini
        // somehow returns array, Jackson bind fails and the call surfaces
        // as MALFORMED via classifyError — a real signal worth investigating,
        // not papered over.
        String arrayShape = "{"
                + "\"summary\":[\"- Core impact: shipped feature\",\"- Stakeholder: client X\"],"
                + "\"goldenMirror\":\"TECHNOLOGY / CORE SKILLS\\n\\nDelivered a thing.\""
                + "}";
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(arrayShape));

        WebApplicationException ex = assertThrows(
                WebApplicationException.class,
                () -> svc.generateInsightsWithUsage(insightsRequest(), null),
                "Post-RS-6 array-shape summary must FAIL parsing — no silent coercion");

        assertEquals(502, ex.getResponse().getStatus(),
                "Array-shape drift surfaces as BAD_GATEWAY → MALFORMED in classifyError");
        assertTrue(ex.getMessage().contains("Invalid AI insights"),
                "Message uses the INSIGHTS-specific marker: " + ex.getMessage());
        // RS-6 follow-up: parse failures after a successful Gemini call must
        // carry telemetry so the FAILED row reflects the real cost spent.
        assertInstanceOf(GeminiTelemetryException.class, ex,
                "Parse failures must be wrapped as GeminiTelemetryException");
        GeminiTelemetryException gte = (GeminiTelemetryException) ex;
        assertEquals(Integer.valueOf(1200), gte.tokenIn,
                "tokenIn from successful Gemini call must propagate to FAILED row");
        assertEquals(Integer.valueOf(800), gte.tokenOut);
        assertEquals("gemini-test", gte.modelUsed);
    }

    // ── validateInsightsResponse: blank summary surfaces as BAD_GATEWAY ──────

    @Test
    void parseInsightsResponse_blankSummary_throwsBadGateway() {
        // Schema can require {summary, goldenMirror} but cannot cheaply express
        // "non-blank string" — validateInsightsResponse fills that gap. The
        // resulting BAD_GATEWAY classifies as MALFORMED in classifyError so
        // the AiJobs row is marked FAILED with a clear tag.
        String blank = "{\"summary\":\"   \",\"goldenMirror\":\"TECHNOLOGY / CORE SKILLS\\n\\nx\"}";
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(blank));

        WebApplicationException ex = assertThrows(
                WebApplicationException.class,
                () -> svc.generateInsightsWithUsage(insightsRequest(), null));

        assertEquals(502, ex.getResponse().getStatus(), "validateInsightsResponse-thrown failures surface as BAD_GATEWAY");
        assertTrue(ex.getMessage().contains("Invalid AI insights"),
                "Message must use the existing INSIGHTS-specific marker for classifyError: " + ex.getMessage());
    }

    @Test
    void parseInsightsResponse_unparseableJson_throwsBadGateway() {
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult("totally not json"));

        WebApplicationException ex = assertThrows(
                WebApplicationException.class,
                () -> svc.generateInsightsWithUsage(insightsRequest(), null));

        assertEquals(502, ex.getResponse().getStatus());
    }

    // ── CC-4c: per-task cache enablement (INSIGHTS) ──────────────────────────

    @Test
    void generateInsights_cacheFlagOff_sendsNoCacheRequest_promptShapeUnchanged() {
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validInsightsJson()));

        svc.generateInsightsWithUsage(insightsRequest(), null);

        ArgumentCaptor<GeminiRequest> reqCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(1)).call(reqCaptor.capture());
        GeminiRequest captured = reqCaptor.getValue();

        org.junit.jupiter.api.Assertions.assertNull(captured.cacheRequest(),
                "Flag OFF: cacheRequest must be null so GeminiClient skips the cache path");
        org.junit.jupiter.api.Assertions.assertNull(captured.uncachedPrompt());
        assertTrue(captured.userPrompt().contains("PERFORMANCE CRITERIA"),
                "Flag OFF: userPrompt must include the cacheable-prefix portion");
        assertTrue(captured.userPrompt().contains("WRITING STYLE REFERENCE"),
                "Flag OFF: userPrompt must include the style portion");
    }

    @Test
    void generateInsights_cacheFlagOn_attachesCacheRequest_correctShape() throws Exception {
        // AC-10.2: when the flag is on, generateInsightsWithUsage must build a
        // CacheRequest keyed on (INSIGHTS, V, discipline, seniority) with the
        // criteria + style block as extraPrefix, dynamic-only as userPrompt,
        // full prompt as uncachedPrompt.
        setField(svc, "insightsCacheEnabled", true);
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validInsightsJson()));

        svc.generateInsightsWithUsage(insightsRequest(), null);

        ArgumentCaptor<GeminiRequest> reqCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(1)).call(reqCaptor.capture());
        GeminiRequest captured = reqCaptor.getValue();

        assertNotNull(captured.cacheRequest(),
                "Flag ON: cacheRequest must be populated");
        CacheRequest cr = captured.cacheRequest();
        assertEquals(TaskMetadata.TASK_INSIGHTS, cr.taskType());
        assertEquals(TaskMetadata.PROMPT_VERSION_INSIGHTS, cr.promptVersion());
        assertEquals("Software & DevOps Engineering", cr.discipline());
        assertEquals("Engineer", cr.seniority());
        assertEquals(1800L, cr.ttlSeconds(),
                "§D5: INSIGHTS uses 30-min TTL (one-shot per submit; managers re-fire on failure)");
        assertEquals("gemini-test", cr.model());
        assertTrue(cr.extraPrefix().contains("PERFORMANCE CRITERIA"),
                "extraPrefix must include the criteria block");
        assertTrue(cr.extraPrefix().contains("WRITING STYLE REFERENCE"),
                "extraPrefix must include the style block");
        // Cross-equality (CC-4b round-2 pattern).
        assertEquals(captured.systemPrompt(), cr.systemPrompt(),
                "CacheRequest.systemPrompt must equal the request's systemPrompt");
        // Dynamic-only userPrompt on cache-ON: PERFORMANCE CRITERIA must NOT
        // leak into userPrompt.
        org.junit.jupiter.api.Assertions.assertFalse(
                captured.userPrompt().contains("PERFORMANCE CRITERIA"),
                "Flag ON: userPrompt must NOT include the cached prefix portion");
    }

    @Test
    void generateInsights_cacheFlagOn_uncachedPromptEqualsCacheOffShape_byteIdentity() throws Exception {
        // Regression guard: uncachedPrompt MUST equal what cache-OFF would
        // have sent — proves the cache-miss fallback delivers identical bytes.
        AiReviewInsightsRequest req = insightsRequest();
        java.util.List<String> tech = (java.util.List<String>) invokeReturning(svc, "criteriaForLevel",
                req.getDiscipline(), req.getSeniority(), "technology");
        java.util.List<String> consulting = (java.util.List<String>) invokeReturning(svc, "criteriaForLevel",
                req.getDiscipline(), req.getSeniority(), "consulting");
        String style = (String) invokeReturning(svc, "loadStyleReference", req.getDiscipline());
        String expectedFullPrompt = svc.buildInsightsCacheablePrefix(req.getDiscipline(),
                       req.getSeniority(), tech, consulting, style)
                + svc.buildInsightsDynamicPart(req);

        setField(svc, "insightsCacheEnabled", true);
        when(geminiClient.call(any(GeminiRequest.class)))
                .thenReturn(geminiResult(validInsightsJson()));
        svc.generateInsightsWithUsage(req, null);

        ArgumentCaptor<GeminiRequest> reqCaptor = ArgumentCaptor.forClass(GeminiRequest.class);
        verify(geminiClient, times(1)).call(reqCaptor.capture());
        GeminiRequest captured = reqCaptor.getValue();

        assertEquals(expectedFullPrompt, captured.uncachedPrompt(),
                "Cache-ON uncachedPrompt must equal cacheable-prefix + dynamic-part");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static GeminiResult geminiResult(String text) {
        return new GeminiResult(text, 1200, 800, "STOP", "gemini-test", false, null);
    }

    /** Reflection helper for invoking private methods that return a value. */
    private static Object invokeReturning(Object target, String method, Object... args) throws Exception {
        var m = java.util.Arrays.stream(target.getClass().getDeclaredMethods())
                .filter(mm -> mm.getName().equals(method) && mm.getParameterCount() == args.length)
                .findFirst().orElseThrow(() -> new IllegalStateException("no method " + method));
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    /** Passes validateInsightsResponse (non-blank summary + goldenMirror). */
    private static String validInsightsJson() {
        return "{"
                + "\"summary\":\"- Core impact: shipped feature on time.\","
                + "\"goldenMirror\":\"TECHNOLOGY / CORE SKILLS\\n\\nSolid delivery in the cycle.\""
                + "}";
    }

    private static AiReviewInsightsRequest insightsRequest() {
        AiReviewInsightsRequest req = new AiReviewInsightsRequest();
        req.setDiscipline("Software & DevOps Engineering");
        req.setSeniority("Engineer");
        AiReviewQuestionAnswer qa = new AiReviewQuestionAnswer();
        qa.setQuestionId("q1");
        qa.setQuestionText("Describe a recent win.");
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

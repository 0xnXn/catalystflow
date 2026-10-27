package com.catalystflow.ai.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ModelResolver}. Bean injects 5 @ConfigProperty fields;
 * tests use reflection to set them directly (the same pattern as the rest of
 * the ai-service test suite — see e.g. AiReviewServiceAnalyzeSchemaTest setField).
 */
class ModelResolverTest {

    private static final String[] ALL_TASKS = {
            TaskMetadata.TASK_ANALYZE,
            TaskMetadata.TASK_INSIGHTS,
            TaskMetadata.TASK_MANAGER_GOLDEN,
            TaskMetadata.TASK_MANAGER_QUESTION_SUGGEST,
            TaskMetadata.TASK_PEER_360_DIGEST,
    };

    private static ModelResolver build(String analyze, String insights, String golden,
                                       String questionSuggest, String digest) {
        ModelResolver r = new ModelResolver();
        setField(r, "analyzeModel", analyze);
        setField(r, "insightsModel", insights);
        setField(r, "goldenModel", golden);
        setField(r, "questionSuggestModel", questionSuggest);
        setField(r, "digestModel", digest);
        // SPEC-503: mirror the application.properties default where each task's
        // fallback resolves to its own primary when GEMINI_MODEL_<TASK>_FALLBACK
        // is unset. Distinct-fallback tests override these via setField.
        setField(r, "analyzeFallbackModel", analyze);
        setField(r, "insightsFallbackModel", insights);
        setField(r, "goldenFallbackModel", golden);
        setField(r, "questionSuggestFallbackModel", questionSuggest);
        setField(r, "digestFallbackModel", digest);
        return r;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void resolves_each_task_to_its_configured_model() {
        ModelResolver r = build(
                "gemini-3.1-flash-lite",   // analyze
                "gemini-3.5-flash",         // insights
                "gemini-2.5-pro",           // golden
                "gemini-3.5-flash",         // question-suggest
                "gemini-3.5-flash"          // digest
        );
        assertEquals("gemini-3.1-flash-lite", r.modelFor(TaskMetadata.TASK_ANALYZE));
        assertEquals("gemini-3.5-flash",      r.modelFor(TaskMetadata.TASK_INSIGHTS));
        assertEquals("gemini-2.5-pro",        r.modelFor(TaskMetadata.TASK_MANAGER_GOLDEN));
        assertEquals("gemini-3.5-flash",      r.modelFor(TaskMetadata.TASK_MANAGER_QUESTION_SUGGEST));
        assertEquals("gemini-3.5-flash",      r.modelFor(TaskMetadata.TASK_PEER_360_DIGEST));
    }

    @Test
    void default_config_resolves_every_task_to_gemini_2_5_flash() {
        // Mirrors the application.properties three-tier fallback when neither
        // GEMINI_MODEL_<TASK> nor GEMINI_MODEL is set.
        ModelResolver r = build(
                "gemini-2.5-flash", "gemini-2.5-flash", "gemini-2.5-flash",
                "gemini-2.5-flash", "gemini-2.5-flash");
        for (String task : new String[]{
                TaskMetadata.TASK_ANALYZE,
                TaskMetadata.TASK_INSIGHTS,
                TaskMetadata.TASK_MANAGER_GOLDEN,
                TaskMetadata.TASK_MANAGER_QUESTION_SUGGEST,
                TaskMetadata.TASK_PEER_360_DIGEST}) {
            assertEquals("gemini-2.5-flash", r.modelFor(task),
                    "task " + task + " must resolve to gemini-2.5-flash under default config");
        }
    }

    @Test
    void unknown_task_type_throws() {
        ModelResolver r = build("a", "b", "c", "d", "e");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> r.modelFor("NOT_A_REAL_TASK"));
        assert ex.getMessage().contains("Unknown taskType");
    }

    @Test
    void null_resolved_model_throws_loudly() {
        ModelResolver r = build(null, "x", "x", "x", "x");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> r.modelFor(TaskMetadata.TASK_ANALYZE));
        assert ex.getMessage().contains("null or blank");
    }

    @Test
    void blank_resolved_model_throws_loudly() {
        ModelResolver r = build("x", "   ", "x", "x", "x");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> r.modelFor(TaskMetadata.TASK_INSIGHTS));
        assert ex.getMessage().contains("null or blank");
    }

    // ── SPEC-503: per-task fallback model + thread-scoped switch ─────────────

    @Test
    void fallbackModelFor_returns_configured_fallback() {
        // CAP-2: fallback configured independently of the primary.
        ModelResolver r = build("gemini-3.1-flash-lite", "gemini-3.5-flash",
                "gemini-3.5-flash", "gemini-3.5-flash", "gemini-3.5-flash");
        for (String task : ALL_TASKS) {
            setField(r, fallbackFieldFor(task), "gemini-2.5-flash");
        }
        for (String task : ALL_TASKS) {
            assertEquals("gemini-2.5-flash", r.fallbackModelFor(task),
                    "task " + task + " must resolve to its configured fallback");
        }
    }

    @Test
    void fallbackModelFor_defaults_to_primary_when_unset() {
        // CAP-3: build() mirrors the application.properties default (fallback ==
        // primary when GEMINI_MODEL_<TASK>_FALLBACK is unset) — byte-identical
        // behaviour to today.
        ModelResolver r = build("gemini-3.1-flash-lite", "gemini-3.5-flash",
                "gemini-2.5-pro", "gemini-3.5-flash", "gemini-3.5-flash");
        for (String task : ALL_TASKS) {
            assertEquals(r.modelFor(task), r.fallbackModelFor(task),
                    "task " + task + " fallback must equal primary when unset");
        }
    }

    @Test
    void fallbackModelFor_unknown_task_throws() {
        ModelResolver r = build("a", "b", "c", "d", "e");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> r.fallbackModelFor("NOT_A_REAL_TASK"));
        assert ex.getMessage().contains("Unknown taskType");
    }

    @Test
    void fallbackModelFor_blank_throws_loudly() {
        ModelResolver r = build("x", "x", "x", "x", "x");
        setField(r, "goldenFallbackModel", "   ");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> r.fallbackModelFor(TaskMetadata.TASK_MANAGER_GOLDEN));
        assert ex.getMessage().contains("null or blank");
    }

    @Test
    void modelFor_returns_fallback_when_preferFallback_active_then_primary_after_clear() {
        // CAP-1 core: the thread-scoped switch flips modelFor() to the fallback.
        ModelResolver r = build("gemini-3.1-flash-lite", "gemini-3.5-flash",
                "gemini-3.5-flash", "gemini-3.5-flash", "gemini-3.5-flash");
        setField(r, "goldenFallbackModel", "gemini-2.5-flash");

        assertEquals("gemini-3.5-flash", r.modelFor(TaskMetadata.TASK_MANAGER_GOLDEN),
                "primary by default");
        r.setPreferFallback(true);
        assertEquals("gemini-2.5-flash", r.modelFor(TaskMetadata.TASK_MANAGER_GOLDEN),
                "fallback while the switch is on");
        r.setPreferFallback(false);
        assertEquals("gemini-3.5-flash", r.modelFor(TaskMetadata.TASK_MANAGER_GOLDEN),
                "primary again after clear");
    }

    @Test
    void preferFallback_is_thread_scoped() throws InterruptedException {
        // The switch must isolate per worker thread — a parallel batch must not
        // bleed one message's fallback preference into another's.
        ModelResolver r = build("a", "b", "gemini-3.5-flash", "d", "e");
        setField(r, "goldenFallbackModel", "gemini-2.5-flash");
        r.setPreferFallback(true);

        String[] otherThreadModel = new String[1];
        Thread t = new Thread(() ->
                otherThreadModel[0] = r.modelFor(TaskMetadata.TASK_MANAGER_GOLDEN));
        t.start();
        t.join();

        assertEquals("gemini-2.5-flash", r.modelFor(TaskMetadata.TASK_MANAGER_GOLDEN),
                "caller thread sees its own fallback preference");
        assertEquals("gemini-3.5-flash", otherThreadModel[0],
                "a different thread is unaffected (ThreadLocal isolation)");
        r.setPreferFallback(false);
    }

    private static String fallbackFieldFor(String task) {
        return switch (task) {
            case TaskMetadata.TASK_ANALYZE -> "analyzeFallbackModel";
            case TaskMetadata.TASK_INSIGHTS -> "insightsFallbackModel";
            case TaskMetadata.TASK_MANAGER_GOLDEN -> "goldenFallbackModel";
            case TaskMetadata.TASK_MANAGER_QUESTION_SUGGEST -> "questionSuggestFallbackModel";
            case TaskMetadata.TASK_PEER_360_DIGEST -> "digestFallbackModel";
            default -> throw new IllegalArgumentException(task);
        };
    }
}

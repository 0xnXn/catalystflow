package com.catalystflow.ai.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Maps a {@link TaskMetadata} task type to its Gemini model identifier.
 *
 * <h2>Why this exists</h2>
 * Different AI flows have different latency/quality tradeoffs:
 * <ul>
 *   <li><b>ANALYZE</b> — interactive, high-frequency, low stakes. Fast cheap model.</li>
 *   <li><b>INSIGHTS / QUESTION_SUGGEST / DIGEST</b> — manager-facing but routine.
 *       Standard Flash tier.</li>
 *   <li><b>MANAGER_GOLDEN</b> — one-shot finalization, quality-critical. Premium
 *       Pro tier worth paying for.</li>
 * </ul>
 *
 * <p>Each task reads its own SAM env var ({@code GEMINI_MODEL_<TASK>}), with a
 * three-tier fallback chain defined in {@code application.properties}:
 * <pre>
 *   gemini.model.&lt;task&gt; = ${GEMINI_MODEL_&lt;TASK&gt;:${GEMINI_MODEL:gemini-2.5-flash}}
 * </pre>
 *
 * <ol>
 *   <li>Per-task env var (e.g. {@code GEMINI_MODEL_ANALYZE}) — preferred.</li>
 *   <li>Global env var ({@code GEMINI_MODEL}) — backward-compat fallback.</li>
 *   <li>Literal default ({@code gemini-2.5-flash}) — safety net so the bean never
 *       fails to inject.</li>
 * </ol>
 *
 * <h2>Cache invariant</h2>
 * {@link CacheRequest#model} is part of the cacheKey hash. Changing the model
 * for a task invalidates its cache rows (intentional — different model =
 * different output even on the same prefix). With all env vars defaulted to
 * {@code gemini-2.5-flash}, behavior is byte-identical to the pre-resolver
 * world: same model on every call = same cacheKey = existing DDB cache rows
 * still hit.
 *
 * <h2>Phase 2 target models</h2>
 * Once the plumbing is verified in prod, the SAM env vars can be flipped
 * per-task without a code redeploy:
 * <ul>
 *   <li>{@code GEMINI_MODEL_ANALYZE = gemini-3.1-flash-lite}</li>
 *   <li>{@code GEMINI_MODEL_INSIGHTS = gemini-3.5-flash}</li>
 *   <li>{@code GEMINI_MODEL_QUESTION_SUGGEST = gemini-3.5-flash}</li>
 *   <li>{@code GEMINI_MODEL_GOLDEN = gemini-2.5-pro} (3.x Pro is preview-only)</li>
 *   <li>{@code GEMINI_MODEL_DIGEST = gemini-3.5-flash}</li>
 * </ul>
 */
@ApplicationScoped
public class ModelResolver {

    @ConfigProperty(name = "gemini.model.analyze")
    String analyzeModel;

    @ConfigProperty(name = "gemini.model.insights")
    String insightsModel;

    @ConfigProperty(name = "gemini.model.golden")
    String goldenModel;

    @ConfigProperty(name = "gemini.model.question-suggest")
    String questionSuggestModel;

    @ConfigProperty(name = "gemini.model.digest")
    String digestModel;

    /**
     * SPEC-503 per-task fallback models. Each resolves via the same three-tier
     * chain as its primary, but defaults to the <em>primary</em> when the
     * {@code GEMINI_MODEL_<TASK>_FALLBACK} env var is unset:
     * <pre>
     *   gemini.model.&lt;task&gt;.fallback = ${GEMINI_MODEL_&lt;TASK&gt;_FALLBACK:${gemini.model.&lt;task&gt;}}
     * </pre>
     * So with no fallback configured, {@code fallbackModelFor == modelFor} and
     * behaviour is byte-identical to today (CAP-3).
     */
    @ConfigProperty(name = "gemini.model.analyze.fallback")
    String analyzeFallbackModel;

    @ConfigProperty(name = "gemini.model.insights.fallback")
    String insightsFallbackModel;

    @ConfigProperty(name = "gemini.model.golden.fallback")
    String goldenFallbackModel;

    @ConfigProperty(name = "gemini.model.question-suggest.fallback")
    String questionSuggestFallbackModel;

    @ConfigProperty(name = "gemini.model.digest.fallback")
    String digestFallbackModel;

    /**
     * SPEC-503 thread-scoped switch. When TRUE on the current thread, {@link
     * #modelFor(String)} returns the task's <em>fallback</em> model. The
     * {@code ai-worker} flips this ON for an SQS redelivery (ApproximateReceiveCount
     * &gt;= 2) and OFF again in a {@code finally}, so a single job execution — which
     * resolves the model at ~12 call sites across this service and
     * {@code ManagerGuidanceService} — uniformly switches with no per-call-site
     * change. Default FALSE → primary. Per-thread by design: a parallel SQS batch
     * must not bleed one message's preference into another's.
     *
     * <p><b>Load-bearing invariant:</b> correctness depends on every {@link
     * #modelFor} call for a job executing on the SAME thread that set this flag,
     * synchronously, before it is cleared. This holds today because {@code
     * runJobInProcess} — including the streaming {@code callStreaming} chunk
     * callback — runs synchronously on the worker pool thread. If Gemini resolution
     * ever moves onto an async/reactive/secondary thread, this switch becomes
     * invisible there and silently no-ops; it MUST then be revisited (pass the tier
     * explicitly or propagate the context across the hop).
     */
    private final ThreadLocal<Boolean> preferFallback = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Set the thread-scoped fallback preference for the calling thread. {@code true}
     * routes subsequent {@link #modelFor} calls to {@link #fallbackModelFor};
     * {@code false} clears it (removes the ThreadLocal entry). Package-private —
     * only {@code AiReviewService} (same package) toggles it; the worker goes
     * through {@code AiReviewService.setModelFallback}.
     */
    void setPreferFallback(boolean prefer) {
        if (prefer) {
            preferFallback.set(Boolean.TRUE);
        } else {
            preferFallback.remove();
        }
    }

    /**
     * Resolve a {@link TaskMetadata#TASK_ANALYZE} (or sibling) constant to the
     * Gemini model identifier configured for that task.
     *
     * @throws IllegalArgumentException for unknown task types
     * @throws IllegalStateException    when the resolved value is null or blank
     *                                  (shouldn't happen given the three-tier
     *                                  fallback in application.properties, but
     *                                  fail-loud rather than send a malformed
     *                                  URL to Gemini)
     */
    public String modelFor(String taskType) {
        // SPEC-503: when the thread-scoped fallback switch is active (set by the
        // worker on an SQS redelivery), resolve the task's fallback model instead
        // of the primary. Default OFF → unchanged behaviour.
        if (Boolean.TRUE.equals(preferFallback.get())) {
            return fallbackModelFor(taskType);
        }
        String resolved = switch (taskType) {
            case TaskMetadata.TASK_ANALYZE -> analyzeModel;
            case TaskMetadata.TASK_INSIGHTS -> insightsModel;
            case TaskMetadata.TASK_MANAGER_GOLDEN -> goldenModel;
            case TaskMetadata.TASK_MANAGER_QUESTION_SUGGEST -> questionSuggestModel;
            case TaskMetadata.TASK_PEER_360_DIGEST -> digestModel;
            default -> throw new IllegalArgumentException(
                    "Unknown taskType for model resolution: " + taskType
                            + " (expected one of TaskMetadata.TASK_*)");
        };
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalStateException(
                    "Resolved model for taskType '" + taskType + "' is null or blank. "
                            + "Check application.properties three-tier fallback "
                            + "(gemini.model.<task> → GEMINI_MODEL → gemini-2.5-flash).");
        }
        return resolved;
    }

    /**
     * SPEC-503: resolve a task type to its configured <em>fallback</em> Gemini
     * model — the one re-run on an SQS redelivery after a transient upstream 5xx.
     * Mirrors {@link #modelFor} (same task switch, same fail-loud guard). With no
     * {@code GEMINI_MODEL_<TASK>_FALLBACK} configured this returns the same value
     * as {@link #modelFor} (the property defaults to the primary), so the feature
     * is inert until a fallback is explicitly set (CAP-3).
     *
     * @throws IllegalArgumentException for unknown task types
     * @throws IllegalStateException    when the resolved value is null or blank
     */
    public String fallbackModelFor(String taskType) {
        String resolved = switch (taskType) {
            case TaskMetadata.TASK_ANALYZE -> analyzeFallbackModel;
            case TaskMetadata.TASK_INSIGHTS -> insightsFallbackModel;
            case TaskMetadata.TASK_MANAGER_GOLDEN -> goldenFallbackModel;
            case TaskMetadata.TASK_MANAGER_QUESTION_SUGGEST -> questionSuggestFallbackModel;
            case TaskMetadata.TASK_PEER_360_DIGEST -> digestFallbackModel;
            default -> throw new IllegalArgumentException(
                    "Unknown taskType for model resolution: " + taskType
                            + " (expected one of TaskMetadata.TASK_*)");
        };
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalStateException(
                    "Resolved fallback model for taskType '" + taskType + "' is null or blank. "
                            + "Check application.properties (gemini.model.<task>.fallback → "
                            + "GEMINI_MODEL_<TASK>_FALLBACK → gemini.model.<task>).");
        }
        return resolved;
    }

    /**
     * Test seam: build a resolver where every task resolves to {@code model}.
     * Mirrors the pre-resolver "single injected gemini.model" world. Used by
     * unit tests that don't care about per-task differences and just need a
     * non-null model string in the request. Package-private so production code
     * can't use it — production goes through the CDI-injected fields.
     */
    static ModelResolver allTasksUseModel(String model) {
        ModelResolver r = new ModelResolver();
        r.analyzeModel = model;
        r.insightsModel = model;
        r.goldenModel = model;
        r.questionSuggestModel = model;
        r.digestModel = model;
        // SPEC-503: mirror the config default (fallback == primary when unset) so
        // fallbackModelFor and modelFor-under-fallback both resolve to `model`.
        r.analyzeFallbackModel = model;
        r.insightsFallbackModel = model;
        r.goldenFallbackModel = model;
        r.questionSuggestFallbackModel = model;
        r.digestFallbackModel = model;
        return r;
    }
}

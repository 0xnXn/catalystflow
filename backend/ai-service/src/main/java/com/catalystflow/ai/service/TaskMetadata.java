package com.catalystflow.ai.service;

/**
 * Canonical per-task constants — task type identifiers and prompt-version
 * strings — used by every layer that needs to attribute work to a flow:
 * {@link AiReviewService} (dispatch), {@link ManagerGuidanceService} (cache
 * opt-in), {@link com.catalystflow.ai.repository.AiJobRepository}
 * (telemetry persistence), {@link CacheRequest} (cacheKey shape).
 *
 * <p>Single source of truth: before this class existed, the same strings
 * lived in both {@code AiReviewService} and {@code ManagerGuidanceService} —
 * a bump on one side could silently disagree with the other. The cacheKey
 * shape relies on the {@code promptVersion} string being consistent across
 * services, so a divergent bump would shard the cache index.
 *
 * <p>To bump a prompt version: edit the constant here and update the
 * matching {@code _V*} test assertions in the dispatcher persistence tests
 * (3 sites today: {@code AiReviewServiceManagerQuestionSuggestPersistenceTest},
 * {@code AiReviewServiceParseFailurePersistenceTest},
 * {@code AiReviewServiceAnalyzeFallbackPersistenceTest}).
 */
public final class TaskMetadata {

    // ── Task type identifiers (AiJobs.taskType column values) ────────────────

    public static final String TASK_ANALYZE = "ANALYZE";
    public static final String TASK_INSIGHTS = "INSIGHTS";
    public static final String TASK_MANAGER_GOLDEN = "MANAGER_GOLDEN";
    public static final String TASK_MANAGER_QUESTION_SUGGEST = "MANAGER_QUESTION_SUGGEST";
    public static final String TASK_PEER_360_DIGEST = "PEER_360_DIGEST";

    // ── Prompt versions (AiJobs.promptVersion column values) ─────────────────
    // Bump the _V suffix when the corresponding system prompt changes
    // meaningfully. Also part of the Gemini cacheKey shape (see CacheRequest)
    // so a bump auto-invalidates that task's cache without operator action.

    public static final String PROMPT_VERSION_ANALYZE = "ANALYZE_V2";
    public static final String PROMPT_VERSION_INSIGHTS = "INSIGHTS_V2";
    public static final String PROMPT_VERSION_MANAGER_GOLDEN = "MANAGER_GOLDEN_V2";
    public static final String PROMPT_VERSION_PEER_360_DIGEST = "PEER_360_DIGEST_V2";
    // QUESTION_SUGGEST_V3: bumped from V2 in the CC-4a follow-up to (a)
    // invalidate any cache rows created with the pre-fix concatenated-user
    // body shape (callCachedContents now emits systemInstruction + contents
    // split — same cacheKey hash, different Gemini-side body, so cached
    // rows from the V2 window would be reused with the wrong shape); and
    // (b) carry the citation-rule rewording (removed "above" directional
    // wording that lied in cache-ON mode). Both changes are invalidating
    // — operators don't need to manually flush DDB.
    public static final String PROMPT_VERSION_QUESTION_SUGGEST = "QUESTION_SUGGEST_V3";

    /**
     * CC-5 follow-up — map a Gemini request task name (kebab-case, used in
     * log lines and {@link GeminiRequest#taskName}) to its canonical
     * {@code TaskMetadata.TASK_*} constant (the value persisted in
     * {@code AiJobs.taskType}).
     *
     * <p>Used by {@link com.catalystflow.ai.service.GeminiClient#emitCacheMetric}
     * to keep the EMF {@code taskType} dimension value aligned with what
     * AiJobs queries / dashboards use — without this, CloudWatch would show
     * {@code "manager-golden"} while AiJobs has {@code "MANAGER_GOLDEN"}
     * and the two can't be joined.
     *
     * <p>Falls back to the input string for unknown task names — that way a
     * future task that forgets to add a mapping at least surfaces a stable
     * value rather than a null dimension. The unmapped value still differs
     * from any AiJobs.taskType so a dashboard mismatch is visible.
     */
    public static String canonicalTaskType(String requestTaskName) {
        if (requestTaskName == null) return null;
        return switch (requestTaskName) {
            case "analyze" -> TASK_ANALYZE;
            case "insights" -> TASK_INSIGHTS;
            case "manager-golden" -> TASK_MANAGER_GOLDEN;
            case "peer-360-digest" -> TASK_PEER_360_DIGEST;
            case "manager-question-suggest" -> TASK_MANAGER_QUESTION_SUGGEST;
            default -> requestTaskName;
        };
    }

    private TaskMetadata() {
        // Static utility — no instances.
    }
}

package com.catalystflow.ai.service;

import com.catalystflow.ai.model.AiReviewInsightsRequest;
import com.catalystflow.ai.model.AiReviewInsightsResponse;
import com.catalystflow.ai.model.AiReviewInsightsStatusResponse;
import com.catalystflow.ai.model.Answer;
import com.catalystflow.ai.model.AiReviewJobResponse;
import com.catalystflow.ai.model.AiReviewJobStatus;
import com.catalystflow.ai.model.AiReviewQuestionAnswer;
import com.catalystflow.ai.model.AiReviewQuestionFeedback;
import com.catalystflow.ai.model.AiReviewRequest;
import com.catalystflow.ai.model.AiReviewResponse;
import com.catalystflow.ai.model.AiReviewStatusResponse;
import com.catalystflow.ai.model.DisciplineInfo;
import com.catalystflow.ai.model.DisciplineSeniorityLevel;
import com.catalystflow.ai.model.ManagerGoldenReview;
import com.catalystflow.ai.model.ManagerJobRequest;
import com.catalystflow.ai.model.QuestionSuggestRequest;
import com.catalystflow.ai.model.TemplateQuestion;
import com.catalystflow.ai.repository.AiJobRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class AiReviewService {

    private static final Logger LOG = Logger.getLogger(AiReviewService.class);
    // Bumped 8192 → 32768 (2026-05-18, pre-demo) for maximum truncation
    // headroom. ANALYZE typical output is ~1.5k; cap at 32k means even the
    // worst-case 10-question template with thinking burn can't hit it.
    // Right-size back down after telemetry shows p95 tokenOut.
    private static final int MAX_OUTPUT_TOKENS = 32_768;
    // INSIGHTS produces summary + a multi-section narrative goldenMirror.
    // Bumped 32768 → 65536 (2026-05-18, pre-demo) to the model's hard ceiling.
    //
    // 2026-05-28: realigned back to 32768 to match MANAGER_GOLDEN. Real-world
    // INSIGHTS output is ~1,500 tokens — the 65k cap was 40× over-provisioned
    // and 2× larger than MANAGER_GOLDEN's cap despite producing less synthesis
    // work (one source vs. three). Untidy inconsistency, not a quality lever:
    // cost and latency are unaffected (we only pay for tokens generated).
    // Still ~16× headroom vs. real output; full right-size waits on p95 telemetry.
    private static final int INSIGHTS_MAX_OUTPUT_TOKENS = 32_768;
    // Task type identifiers — single source of truth at TaskMetadata.
    // Re-exported here so existing in-class references compile without churn.
    private static final String TASK_ANALYZE = TaskMetadata.TASK_ANALYZE;
    private static final String TASK_INSIGHTS = TaskMetadata.TASK_INSIGHTS;
    static final String TASK_MANAGER_GOLDEN = TaskMetadata.TASK_MANAGER_GOLDEN;
    static final String TASK_MANAGER_QUESTION_SUGGEST = TaskMetadata.TASK_MANAGER_QUESTION_SUGGEST;
    static final String TASK_PEER_360_DIGEST = TaskMetadata.TASK_PEER_360_DIGEST;
    // TTL: 30 days from job creation. Originally 24 hours, but that was too
    // short for the manager-review workflow — drafts routinely sit for several
    // days while the manager finishes other work, and by the time they came
    // back the AiJobs row referenced by the review's managerGoldenJobId /
    // insightsJobId field had been TTL-reaped from DDB, leaving the UI to
    // show "Golden review unavailable" on a job that was actually completed
    // successfully. 30 days covers a typical cycle-close window plus a
    // post-cycle inspection grace period. DDB storage cost remains
    // negligible (~$0.04/month at 1000 jobs/month × 30 days × 5 KB each).
    private static final long JOB_TTL_SECONDS = 30 * 86_400L;

    /**
     * Matches the "upstream &lt;code&gt;" suffix in GeminiClient's BAD_GATEWAY
     * wrapper messages, e.g. "AI service error (upstream 400)" — used by
     * {@link #classifyError} so AiJobs.errorMessage buckets on the REAL
     * upstream code rather than the JAX-RS wrapper's always-502 status.
     */
    private static final java.util.regex.Pattern UPSTREAM_CODE_PATTERN =
            java.util.regex.Pattern.compile("upstream (\\d{3})");

    // Prompt versions — written to the AiJobs.promptVersion column. Single
    // source of truth at TaskMetadata so callers (this service and
    // ManagerGuidanceService for cache opt-in) can never disagree on the
    // string — divergence would shard the Gemini cache index.
    private static final String PROMPT_VERSION_ANALYZE = TaskMetadata.PROMPT_VERSION_ANALYZE;
    private static final String PROMPT_VERSION_INSIGHTS = TaskMetadata.PROMPT_VERSION_INSIGHTS;
    private static final String PROMPT_VERSION_MANAGER_GOLDEN = TaskMetadata.PROMPT_VERSION_MANAGER_GOLDEN;
    private static final String PROMPT_VERSION_PEER_360_DIGEST = TaskMetadata.PROMPT_VERSION_PEER_360_DIGEST;
    private static final String PROMPT_VERSION_QUESTION_SUGGEST = TaskMetadata.PROMPT_VERSION_QUESTION_SUGGEST;

    // Thinking-token budgets per task (Gemini 2.5 series). Counts INSIDE
    // maxOutputTokens — if the model burns >budget on internal reasoning, it
    // stops thinking and writes the answer. Sized small enough that visible
    // JSON has ample room; sized large enough that procedural reasoning works.
    // Incident bc02bfa2 (2026-05-17) and incident feac9e10 (2026-05-18) both
    // hit MAX_TOKENS truncation despite small expected outputs — the cause
    // was unbounded thinking. Capping it leaves output budget for actual prose.
    // Bumped 1024 → 4096 and 2048 → 12288 (2026-05-18, pre-demo) so the
    // model has plenty of reasoning headroom while still leaving most of
    // maxOutputTokens for visible JSON. Thinking always stays well below
    // its enclosing cap.
    //
    // 2026-05-28: right-sized INSIGHTS 12288 → 2048 after latency profiling
    // showed ~52s TTFT on gemini-3.5-flash was almost entirely thinking-phase.
    // INSIGHTS is summarization (bullets + 3 narrative sections), not deep CoT
    // — 2048 reasoning tokens is ample. Expected TTFT drop: ~52s → ~8s while
    // the SSE stream feeds the manager-side preview. ANALYZE stays on
    // flash-lite which ignores thinkingBudget; the cap is academic there.
    private static final int THINKING_BUDGET_ANALYZE  = 4096;
    private static final int THINKING_BUDGET_INSIGHTS = 2048;

    /**
     * Wrapper used by the worker path to thread Gemini token usage to AiJobs.
     * CC-5: also carries {@code cacheHit} + {@code cachedTokens} so the worker
     * can persist cache attribution on the AiJobs row (AC-11.1).
     */
    record AnalyzeResult(AiReviewResponse response, Integer tokenIn, Integer tokenOut,
                         Boolean cacheHit, Integer cachedTokens) {}

    /**
     * Wrapper used by the worker path to thread Gemini token usage to AiJobs.
     * CC-5: same shape as {@link AnalyzeResult} — cache attribution flows
     * INSIGHTS → AiJobs row identically.
     */
    record InsightsResult(AiReviewInsightsResponse response, Integer tokenIn, Integer tokenOut,
                          Boolean cacheHit, Integer cachedTokens) {}

    @Inject
    AiJobRepository aiJobRepository;

    @Inject
    SqsClient sqs;

    @Inject
    DynamoDbClient dynamoDb;

    @Inject
    ManagerGuidanceService managerGuidanceService;

    @Inject
    GeminiClient geminiClient;

    // Optional<String> rather than a plain String — SmallRye config maps empty values
    // to null, so an unset AI_JOBS_QUEUE_URL would fail @ConfigProperty injection.
    // Optional handles "set to a real URL" vs "absent (run in-process)" cleanly.
    @ConfigProperty(name = "ai-jobs.queue-url")
    Optional<String> jobsQueueUrl;

    String geminiApiKey = "";

    @Inject
    void configureGeminiApiKey(@ConfigProperty(name = "gemini.api-key") java.util.Optional<String> configuredKey) {
        geminiApiKey = configuredKey.orElse("");
    }

    // Per-task model selection is delegated to ModelResolver, which reads
    // gemini.model.<task> (with three-tier fallback to GEMINI_MODEL env var
    // then the literal default). This replaces the previous single-injected
    // geminiModel field — now ANALYZE and INSIGHTS can land on different
    // Gemini models independently.
    @Inject
    ModelResolver modelResolver;

    @ConfigProperty(name = "ai-review.mock", defaultValue = "false")
    boolean mockMode;

    @ConfigProperty(name = "reviews.table-name", defaultValue = "CatalystFlow_Reviews")
    String reviewsTable;

    @ConfigProperty(name = "review-templates.table-name", defaultValue = "CatalystFlow_ReviewTemplates")
    String templatesTable;

    @ConfigProperty(name = "ai-review.provider-fallback", defaultValue = "true")
    boolean providerFallbackEnabled;

    /**
     * CC-4c: per-task cache enablement for the ANALYZE flow (mirror of
     * {@code ManagerGuidanceService.questionSuggestCacheEnabled}). Default
     * OFF — flip via SAM env var {@code GEMINI_CACHE_ENABLED_ANALYZE=true}.
     *
     * <p>The cacheable portion is the static-per-(discipline, seniority)
     * block: PERFORMANCE CRITERIA + tech criteria + consulting criteria +
     * QUALITY SIGNALS + STYLE GUIDANCE. Typically several thousand tokens
     * — well above the {@code MIN_CACHEABLE_TOKENS} threshold so caching
     * actually activates (unlike GOLDEN/DIGEST under CC-4b). §D5 TTL: 1h.
     */
    @ConfigProperty(name = "gemini-cache.enabled.analyze", defaultValue = "false")
    boolean analyzeCacheEnabled;

    /**
     * CC-4c: mirror of {@link #analyzeCacheEnabled} for INSIGHTS. SAM env
     * var {@code GEMINI_CACHE_ENABLED_INSIGHTS=true}. §D5 TTL: 30 min
     * (one-shot per submit, but managers re-fire on failures so reuse
     * window is non-zero).
     */
    @ConfigProperty(name = "gemini-cache.enabled.insights", defaultValue = "false")
    boolean insightsCacheEnabled;

    private final ObjectMapper mapper = new ObjectMapper();
    private JsonNode criteriaTree;
    private JsonNode styleReferences;
    private JsonNode managerStyleReferences;

    @PostConstruct
    void init() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("performance-criteria.json")) {
            if (is == null) throw new RuntimeException("performance-criteria.json not found in classpath");
            criteriaTree = mapper.readTree(is);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load performance criteria", e);
        }
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("style-references.json")) {
            if (is == null) throw new RuntimeException("style-references.json not found in classpath");
            styleReferences = mapper.readTree(is);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load style references", e);
        }
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("manager-style-references.v2-sectioned.json")) {
            if (is != null) {
                managerStyleReferences = mapper.readTree(is);
            }
        } catch (IOException e) {
            LOG.warnf(e, "Failed to load manager-style-references.v2-sectioned.json");
        }
    }

    // ── Async job dispatch ────────────────────────────────────────────────────

    public AiReviewJobResponse startAnalysis(AiReviewRequest request) {
        return startJob(TASK_ANALYZE, request, "AI review job");
    }

    public AiReviewJobResponse startInsights(AiReviewInsightsRequest request) {
        return startJob(TASK_INSIGHTS, request, "AI review insights job");
    }

    /** Manager golden-rewrite (G3): enqueue → worker → polled by frontend.
     *  BUG-001: refuse to regenerate the narrative of a terminal (COMPLETED/PUBLISHED)
     *  review — once submitted/published it is frozen. */
    public AiReviewJobResponse startManagerGolden(String reviewId, String requestedBy) {
        assertReviewNarrativeEditable(reviewId);
        ManagerJobRequest req = ManagerJobRequest.of(TASK_MANAGER_GOLDEN, reviewId, requestedBy);
        return startJob(TASK_MANAGER_GOLDEN, req, "manager golden job");
    }

    /**
     * Review statuses on which the AI narrative is immutable. Mirrors the frontend
     * {@code viewOnly} lock (manager-review-form.component.ts — COMPLETED || PUBLISHED)
     * so the server and UI agree on when the narrative is frozen. BUG-001 / OBS-B.
     */
    private static final Set<String> TERMINAL_REVIEW_STATUSES = Set.of("COMPLETED", "PUBLISHED");

    /**
     * Guard for AI-narrative mutations (regenerate / edit). Throws
     * {@link ReviewLockedException} (→ 409) when the review is terminal.
     *
     * <p>Fail-open on read failure: if the review can't be loaded (infra blip,
     * not-found), we log and allow the op rather than convert a transient error
     * into a false lock — the caller's own not-found handling still applies, and a
     * first generation on a fresh review must never be blocked by a flaky read.
     */
    private void assertReviewNarrativeEditable(String reviewId) {
        if (reviewId == null || reviewId.isBlank()) return;
        String status;
        try {
            status = getReviewStatus(reviewId);
        } catch (Exception e) {
            LOG.warnf(e, "Could not read review status for %s; allowing narrative op", reviewId);
            return;
        }
        if (status != null && TERMINAL_REVIEW_STATUSES.contains(status)) {
            throw new ReviewLockedException(
                    "This review is " + status + " — its AI narrative is locked and cannot be "
                    + "regenerated or edited.");
        }
    }

    /** Lightweight status-only read of a review row. Null if the row is absent. */
    private String getReviewStatus(String reviewId) {
        var res = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(reviewsTable)
                .key(Map.of("reviewId", AttributeValue.builder().s(reviewId).build()))
                .build());
        if (!res.hasItem() || res.item().isEmpty()) return null;
        return str(res.item(), "status");
    }

    /** Resolve the reviewId persisted on a manager job's requestJson. Null if absent
     *  or unparseable. Mirrors {@link #getJobOwner} which reads requestedBy. */
    private String getJobReviewId(String jobId) {
        String requestJson = aiJobRepository.getRequestJson(jobId);
        if (requestJson == null) return null;
        try {
            JsonNode node = mapper.readTree(requestJson).get("reviewId");
            return (node == null || node.isNull()) ? null : node.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Per-question manager suggestion (G3): enqueue. The request body is
     * passed through verbatim — the worker re-fetches the live review snapshot
     * by id, but per-question Gemini context (question text, expected response,
     * current answer) is captured at enqueue time.
     */
    public AiReviewJobResponse startManagerQuestionSuggest(String reviewId,
                                                           QuestionSuggestRequest req) {
        ManagerJobRequest envelope = ManagerJobRequest.of(TASK_MANAGER_QUESTION_SUGGEST, reviewId,
                req.questionId);
        envelope.payload = req;
        return startJob(TASK_MANAGER_QUESTION_SUGGEST, envelope, "manager question-suggest job");
    }

    /**
     * Peer-360 digest (ADR-2): clusters all completed PEER_360 rows for a
     * single (cycleId, subjectUserId) pair into themes/consensus/dissent.
     * The worker re-queries the GSI at run time — keeps the snapshot fresh
     * if a peer completes between enqueue and dispatch.
     */
    public AiReviewJobResponse startPeer360Digest(String cycleId, String subjectUserId,
                                                  String requestedBy) {
        com.catalystflow.ai.model.Peer360DigestJobRequest req =
                com.catalystflow.ai.model.Peer360DigestJobRequest.of(
                        TASK_PEER_360_DIGEST, cycleId, subjectUserId, requestedBy);
        // Pass (subject, cycle) so the AiJobs row is indexed (Peer360DigestIndex)
        // for the resolve-by-(subject,cycle) lookup — the digest is reusable
        // without being pinned to any review row.
        return startJob(TASK_PEER_360_DIGEST, req, "peer-360 digest job", subjectUserId, cycleId);
    }

    /**
     * Resolve the latest COMPLETED peer-360 digest jobId for a (subject, cycle),
     * or {@code null}. Backs {@code GET .../peer360-digest/latest} so the UI
     * reuses a finished digest on refresh without a review-stored pointer.
     */
    public String latestCompletedPeer360Digest(String cycleId, String subjectUserId) {
        return aiJobRepository.latestCompletedDigest(subjectUserId, cycleId);
    }

    private AiReviewJobResponse startJob(String taskType, Object request, String label) {
        return startJob(taskType, request, label, null, null);
    }

    private AiReviewJobResponse startJob(String taskType, Object request, String label,
                                         String subjectUserId, String cycleId) {
        String jobId = UUID.randomUUID().toString();
        long expiresAt = Instant.now().getEpochSecond() + JOB_TTL_SECONDS;

        try {
            String requestJson = mapper.writeValueAsString(request);
            if (subjectUserId != null && cycleId != null) {
                // Digest jobs index by (subject, cycle) for later resolve.
                aiJobRepository.createForDigest(jobId, taskType, requestJson, expiresAt,
                        subjectUserId, cycleId, Instant.now().getEpochSecond());
            } else {
                aiJobRepository.create(jobId, taskType, requestJson, expiresAt);
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to persist AI job %s to DynamoDB", jobId);
            throw new WebApplicationException("Failed to start " + label, Response.Status.INTERNAL_SERVER_ERROR);
        }

        // G2: enqueue rather than running Gemini in-process. The AiWorker Lambda
        // picks up the message and writes the result back to DDB; the frontend
        // polls /ai-review/analyze/{jobId}/status until the row is COMPLETED.
        // Falls back to in-process execution when no queue URL is configured —
        // keeps unit tests and local dev working without an SQS endpoint.
        if (jobsQueueUrl.isEmpty()) {
            LOG.warnf("ai-jobs.queue-url not set — running job %s in process (sync mode)", jobId);
            runJobInProcess(jobId);
        } else {
            try {
                sqs.sendMessage(SendMessageRequest.builder()
                        .queueUrl(jobsQueueUrl.get())
                        .messageBody(jobId)
                        .build());
                LOG.infof("Enqueued AI job %s for async processing", jobId);
            } catch (Exception e) {
                LOG.errorf(e, "Failed to enqueue AI job %s; marking FAILED", jobId);
                aiJobRepository.updateFailed(jobId, "Failed to enqueue: " + e.getMessage());
                throw new WebApplicationException("Failed to start " + label, Response.Status.INTERNAL_SERVER_ERROR);
            }
        }

        AiReviewJobResponse response = new AiReviewJobResponse();
        response.setJobId(jobId);
        response.setStatus(AiReviewJobStatus.PENDING);
        return response;
    }

    /**
     * SPEC-503: set the thread-scoped model-fallback preference for the current
     * thread, then run the job as usual. The {@code ai-worker} calls this with
     * {@code true} on an SQS redelivery (ApproximateReceiveCount &gt;= 2) just
     * before {@link #runJobInProcess(String)} and {@code false} afterwards in a
     * {@code finally}, so that round's Gemini call(s) resolve the task's fallback
     * model. Delegates to {@link ModelResolver#setPreferFallback}; no-op-safe
     * ({@code false} simply clears the flag). Has no effect when no fallback model
     * is configured for the task (fallback defaults to the primary).
     *
     * <p>Thread-scoped: this call and the ensuing {@link #runJobInProcess} MUST run
     * on the same thread (they do — the worker invokes both on its pool thread),
     * since the preference lives in a {@code ThreadLocal} on {@link ModelResolver}.
     */
    public void setModelFallback(boolean preferFallback) {
        modelResolver.setPreferFallback(preferFallback);
    }

    public void runJobInProcess(String jobId) {
        String requestJson = aiJobRepository.getRequestJson(jobId);
        if (requestJson == null) {
            LOG.warnf("AI job %s not found in DynamoDB — marking as failed", jobId);
            aiJobRepository.updateFailed(jobId, "Job data not found");
            return;
        }

        aiJobRepository.updateProcessing(jobId);
        // Hoisted out of the try so the catch blocks can map taskType →
        // promptVersion when persisting telemetry on the failure path.
        String taskType = aiJobRepository.getTaskType(jobId);
        try {
            if (TASK_INSIGHTS.equals(taskType)) {
                AiReviewInsightsRequest request = mapper.readValue(requestJson, AiReviewInsightsRequest.class);
                // Pass jobId so generateInsightsWithUsage routes Gemini via
                // the streaming endpoint and persists partialText to AiJobs.
                InsightsResult result = generateInsightsWithUsage(request, jobId);
                aiJobRepository.updateCompleted(jobId,
                        mapper.writeValueAsString(result.response()),
                        result.tokenIn(), result.tokenOut(),
                        modelResolver.modelFor(TASK_INSIGHTS), PROMPT_VERSION_INSIGHTS, null,
                        result.cacheHit(), result.cachedTokens());
                // Streaming partialText is superseded by the structured result —
                // clear it so a COMPLETED row doesn't carry a stale half-stream for
                // the full TTL. Matches the GOLDEN / DIGEST branches below.
                aiJobRepository.clearPartialText(jobId);
            } else if (TASK_MANAGER_GOLDEN.equals(taskType)) {
                ManagerJobRequest req = mapper.readValue(requestJson, ManagerJobRequest.class);
                ReviewSnapshot snap = getReviewSnapshot(req.reviewId);
                // Pass jobId so generateGoldenReview routes Gemini via the
                // streaming endpoint and persists partialText to AiJobs.
                ManagerGuidanceService.GoldenResult result = computeManagerGolden(snap, jobId);
                aiJobRepository.updateCompleted(jobId,
                        mapper.writeValueAsString(result.golden),
                        result.tokenIn, result.tokenOut,
                        modelResolver.modelFor(TASK_MANAGER_GOLDEN), PROMPT_VERSION_MANAGER_GOLDEN, result.repairRetries,
                        result.cacheHit, result.cachedTokens);
                // Streaming partialText is no longer relevant once the
                // structured result is persisted — clear before the row settles.
                aiJobRepository.clearPartialText(jobId);
            } else if (TASK_PEER_360_DIGEST.equals(taskType)) {
                com.catalystflow.ai.model.Peer360DigestJobRequest req =
                        mapper.readValue(requestJson, com.catalystflow.ai.model.Peer360DigestJobRequest.class);
                ManagerGuidanceService.DigestResult result =
                        computePeer360Digest(req.cycleId, req.subjectUserId, jobId);
                aiJobRepository.updateCompleted(jobId,
                        mapper.writeValueAsString(result.digest),
                        result.tokenIn, result.tokenOut,
                        modelResolver.modelFor(TASK_PEER_360_DIGEST), PROMPT_VERSION_PEER_360_DIGEST, result.repairRetries,
                        result.cacheHit, result.cachedTokens);
                aiJobRepository.clearPartialText(jobId);
            } else if (TASK_MANAGER_QUESTION_SUGGEST.equals(taskType)) {
                ManagerJobRequest req = mapper.readValue(requestJson, ManagerJobRequest.class);
                if (req.payload == null) {
                    throw new BadRequestException("question-suggest job missing payload");
                }
                List<String> techCriteria = criteriaForLevel(
                        req.payload.discipline, req.payload.seniority, "technology");
                List<String> consultingCriteria = criteriaForLevel(
                        req.payload.discipline, req.payload.seniority, "consulting");
                String managerStyle = managerStyleReferences == null
                        ? ""
                        : managerStyleReferences.toString();
                ManagerGuidanceService.SuggestionResult result = managerGuidanceService.suggestForQuestion(
                        req.payload, techCriteria, consultingCriteria, managerStyle);
                aiJobRepository.updateCompleted(jobId,
                        mapper.writeValueAsString(result),
                        result.tokenIn, result.tokenOut,
                        modelResolver.modelFor(TASK_MANAGER_QUESTION_SUGGEST), PROMPT_VERSION_QUESTION_SUGGEST, result.repairRetries,
                        result.cacheHit, result.cachedTokens);
            } else {
                // Default — also covers null taskType from legacy rows.
                AiReviewRequest request = mapper.readValue(requestJson, AiReviewRequest.class);
                AnalyzeResult result = analyzeWithUsage(request);
                aiJobRepository.updateCompleted(jobId,
                        mapper.writeValueAsString(result.response()),
                        result.tokenIn(), result.tokenOut(),
                        modelResolver.modelFor(TASK_ANALYZE), PROMPT_VERSION_ANALYZE, null,
                        result.cacheHit(), result.cachedTokens());
            }
            LOG.infof("AI job %s completed successfully", jobId);
        } catch (BadRequestException e) {
            LOG.warnf("AI job %s failed (bad request): %s", jobId, e.getMessage());
            aiJobRepository.updateFailed(jobId, e.getMessage());
            if (TASK_INSIGHTS.equals(taskType)
                    || TASK_MANAGER_GOLDEN.equals(taskType)
                    || TASK_PEER_360_DIGEST.equals(taskType)) {
                aiJobRepository.clearPartialText(jobId);
            }
        } catch (GeminiRateLimitedException e) {
            // Do NOT mark FAILED — bubble up to AiWorkerHandler which will defer
            // the job via SQS ChangeMessageVisibility (Option A retry path). The
            // AiJobs row stays in PROCESSING; the next worker invocation skips
            // tryClaim (PROCESSING is OK to re-run because Gemini is idempotent)
            // and tries again with a fresh per-minute quota window.
            //
            // If SQS exhausts maxReceiveCount=3 receives, the worker handler is
            // responsible for the final FAILED transition before the message
            // moves to the DLQ.
            throw e;
        } catch (GeminiTransientUpstreamException e) {
            // Same rationale as the 429 path above: a transient upstream failure
            // (5xx 500/502/503 — e.g. 503 "model overloaded" — or a post-retry IO
            // failure) is NOT terminal. Bubble up to AiWorkerHandler which defers
            // the job via SQS ChangeMessageVisibility; the AiJobs row stays
            // PROCESSING and re-runs on redelivery. Only after the worker exhausts
            // MAX_RECEIVES_BEFORE_DLQ does it write FAILED. MUST precede the generic
            // catch (Exception) below, which would otherwise mark the row FAILED on
            // the first transient blip (the bug this fix addresses).
            throw e;
        } catch (com.catalystflow.ai.model.SchemaInvalidException e) {
            LOG.errorf("AI job %s failed schema validation: tokenIn=%s tokenOut=%s repairRetries=%s model=%s cacheHit=%s cachedTokens=%s errors=%s",
                    jobId, e.tokenIn, e.tokenOut, e.repairRetries, e.modelUsed,
                    e.cacheHit, e.cachedTokens, e.getErrors());
            // RS-2 follow-up: telemetry now travels on the exception (was
            // discarded with the unreturned result wrapper pre-fix). Falls
            // back to the configured model when the exception lacks one
            // (legacy constructor path).
            //
            // CC-5 follow-up: cache attribution also travels on the exception
            // — failed cached calls cost real tokens too, and the FAILED row
            // attribution would be incomplete without it.
            // Legacy fallback when the exception was constructed without a
            // modelUsed value. Maps taskType → resolved model so the FAILED
            // row still attributes to the right model. Unknown / null taskType
            // (legacy rows) defaults to ANALYZE, matching the dispatcher
            // fallthrough at the success path above.
            String fallbackModel;
            try {
                fallbackModel = modelResolver.modelFor(taskType != null ? taskType : TASK_ANALYZE);
            } catch (IllegalArgumentException unknownTask) {
                fallbackModel = modelResolver.modelFor(TASK_ANALYZE);
            }
            aiJobRepository.updateFailed(jobId, "SCHEMA_INVALID: " + e.getMessage(),
                    e.tokenIn, e.tokenOut,
                    e.modelUsed != null ? e.modelUsed : fallbackModel,
                    promptVersionFor(taskType),
                    e.repairRetries,
                    e.cacheHit, e.cachedTokens);
            if (TASK_INSIGHTS.equals(taskType)
                    || TASK_MANAGER_GOLDEN.equals(taskType)
                    || TASK_PEER_360_DIGEST.equals(taskType)) {
                aiJobRepository.clearPartialText(jobId);
            }
        } catch (GeminiTelemetryException e) {
            // Truncation / safety-block / no-candidates path. The exception
            // carries usageMetadata captured BEFORE the failure was raised,
            // so we persist token spend even though the job is FAILED — cost
            // attribution must include failed runs (Gemini bills for the
            // tokens generated regardless of finishReason).
            //
            // CC-5 follow-up: cache attribution also persisted — a truncated
            // cached call still counted against the cache's cost ledger.
            LOG.errorf("AI job %s failed Gemini call: tokenIn=%s tokenOut=%s model=%s cacheHit=%s cachedTokens=%s",
                    jobId, e.tokenIn, e.tokenOut, e.modelUsed, e.cacheHit, e.cachedTokens);
            aiJobRepository.updateFailed(jobId, classifyError(e),
                    e.tokenIn, e.tokenOut, e.modelUsed, promptVersionFor(taskType),
                    null, e.cacheHit, e.cachedTokens);
            if (TASK_INSIGHTS.equals(taskType)
                    || TASK_MANAGER_GOLDEN.equals(taskType)
                    || TASK_PEER_360_DIGEST.equals(taskType)) {
                aiJobRepository.clearPartialText(jobId);
            }
        } catch (Exception e) {
            LOG.errorf(e, "AI job %s failed unexpectedly", jobId);
            // Walk the cause chain — Quarkus may wrap our GeminiTelemetryException
            // in a CDI proxy exception. Recover the telemetry if available.
            GeminiTelemetryException tele = findTelemetryException(e);
            if (tele != null) {
                aiJobRepository.updateFailed(jobId, classifyError(e),
                        tele.tokenIn, tele.tokenOut, tele.modelUsed, promptVersionFor(taskType),
                        null, tele.cacheHit, tele.cachedTokens);
            } else {
                aiJobRepository.updateFailed(jobId, classifyError(e));
            }
            if (TASK_INSIGHTS.equals(taskType)
                    || TASK_MANAGER_GOLDEN.equals(taskType)
                    || TASK_PEER_360_DIGEST.equals(taskType)) {
                aiJobRepository.clearPartialText(jobId);
            }
        }
    }

    private static GeminiTelemetryException findTelemetryException(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof GeminiTelemetryException g) return g;
        }
        return null;
    }

    private static String promptVersionFor(String taskType) {
        if (taskType == null) return null;
        return switch (taskType) {
            case TASK_ANALYZE -> PROMPT_VERSION_ANALYZE;
            case TASK_INSIGHTS -> PROMPT_VERSION_INSIGHTS;
            case TASK_MANAGER_GOLDEN -> PROMPT_VERSION_MANAGER_GOLDEN;
            case TASK_PEER_360_DIGEST -> PROMPT_VERSION_PEER_360_DIGEST;
            case TASK_MANAGER_QUESTION_SUGGEST -> PROMPT_VERSION_QUESTION_SUGGEST;
            default -> null;
        };
    }

    /**
     * Map a thrown exception to a tagged errorMessage that gets written to
     * AiJobs. The frontend (and any debugging human) can branch on the tag
     * prefix without parsing free-form prose. Walks the cause chain so a
     * wrapped HttpTimeoutException is still recognised as TIMEOUT.
     *
     * Recognised tags:
     *   TIMEOUT       — Gemini HTTP call exceeded its 500 s budget
     *   TRUNCATED     — Gemini returned 200 but finishReason=MAX_TOKENS (raise maxOutputTokens)
     *   BLOCKED       — Gemini refused to complete (finishReason=SAFETY/RECITATION/…)
     *   MALFORMED     — Gemini returned 200 but body did not parse / matched the schema
     *   UPSTREAM_5XX  — Gemini returned 5xx (network OK, model side broken)
     *   UPSTREAM_4XX  — Gemini returned 4xx (likely auth / quota / prompt issue)
     *   UNAVAILABLE   — connection/IO failure reaching Gemini (post-retry)
     *   UNKNOWN       — everything else; includes exception class name for triage
     */
    public static String classifyError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException) {
                return "TIMEOUT: " + nullSafe(t.getMessage());
            }
            // Transient upstream (deferred-retry exhausted by the worker). Tag by
            // the real Gemini status when known; upstreamCode 0 means the cause was
            // an IO failure, so report it as UNAVAILABLE rather than a fake 5xx.
            if (t instanceof GeminiTransientUpstreamException gte) {
                return gte.upstreamCode >= 500
                        ? "UPSTREAM_5XX (" + gte.upstreamCode + "): " + nullSafe(gte.getMessage())
                        : "UNAVAILABLE: " + nullSafe(gte.getMessage());
            }
            if (t instanceof WebApplicationException wae && wae.getResponse() != null) {
                int code = wae.getResponse().getStatus();
                String msg = nullSafe(wae.getMessage());
                // Distinguish "we threw 502 because the body was unreadable"
                // from "Gemini actually returned a 5xx HTTP status". The former
                // is a parsing/truncation issue, not an upstream outage —
                // calling it UPSTREAM_5XX in AiJobs misleads anyone triaging.
                if (msg.startsWith("Response truncated")) {
                    return "TRUNCATED: " + msg;
                }
                if (msg.startsWith("Response blocked by upstream safety filter")) {
                    return "BLOCKED: " + msg;
                }
                if (msg.startsWith("Invalid AI")
                        || msg.contains("malformed upstream")
                        || msg.contains("no candidates returned")) {
                    return "MALFORMED: " + msg;
                }
                // Quota-exceeded path from the 429-retry block in callGemini —
                // tag separately from generic UPSTREAM_4XX so ops dashboards can
                // split "we got throttled" from "we asked for something invalid".
                if (msg.startsWith("RATE_LIMITED") || code == 429) {
                    return "RATE_LIMITED: " + msg;
                }
                // RS-1 fix: GeminiClient wraps every non-200 / non-429 upstream
                // response in WebApplicationException(BAD_GATEWAY) regardless of
                // the real upstream HTTP code. So wae.getStatus() is always 502
                // here — bucketing on it would mis-classify a real upstream 400
                // as UPSTREAM_5XX (502). The real code is embedded in the message
                // template "AI service error (upstream <code>)" — parse it first
                // so AiJobs.errorMessage tags reflect the actual Gemini response.
                java.util.regex.Matcher upstreamMatcher = UPSTREAM_CODE_PATTERN.matcher(msg);
                if (upstreamMatcher.find()) {
                    int upstreamCode = Integer.parseInt(upstreamMatcher.group(1));
                    if (upstreamCode == 504) return "TIMEOUT: " + msg;
                    if (upstreamCode >= 500) return "UPSTREAM_5XX (" + upstreamCode + "): " + msg;
                    if (upstreamCode >= 400) return "UPSTREAM_4XX (" + upstreamCode + "): " + msg;
                }
                if (code == 504) return "TIMEOUT: " + msg;
                if (code >= 500) return "UPSTREAM_5XX (" + code + "): " + msg;
                if (code >= 400) return "UPSTREAM_4XX (" + code + "): " + msg;
            }
            // Order matters: JsonProcessingException extends IOException in Jackson,
            // so the JSON case must be checked first or it gets miscategorised.
            if (t instanceof com.fasterxml.jackson.core.JsonProcessingException) {
                return "MALFORMED: " + nullSafe(t.getMessage());
            }
            if (t instanceof java.io.IOException) {
                return "UNAVAILABLE: " + t.getClass().getSimpleName() + ": " + nullSafe(t.getMessage());
            }
        }
        String msg = nullSafe(e.getMessage());
        // Heuristic for the RuntimeException wrapper from ManagerGuidanceService
        // — the wrapped exception lives in getCause(), already covered above, but
        // legacy messages may still carry the marker text.
        if (msg.contains("AI service timeout")) return "TIMEOUT: " + msg;
        if (msg.contains("AI service unavailable")) return "UNAVAILABLE: " + msg;
        if (msg.startsWith("RATE_LIMITED")) return "RATE_LIMITED: " + msg;
        return "UNKNOWN: " + e.getClass().getSimpleName() + ": " + msg;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    // parseRetryAfterMs moved to GeminiClient in RS-1. The deprecated forwarding
    // shim further down (in the "Gemini API call" comment block) preserves the
    // AiReviewService.parseRetryAfterMs(...) call site for any external caller
    // until the next cleanup pass.

    public AiReviewStatusResponse getAnalysisStatus(String jobId) {
        AiReviewStatusResponse status = aiJobRepository.get(jobId);
        if (status == null) return null;

        if (status.getStatus() == AiReviewJobStatus.COMPLETED) {
            String resultJson = aiJobRepository.getResultJson(jobId);
            if (resultJson != null) {
                try {
                    status.setResult(mapper.readValue(resultJson, AiReviewResponse.class));
                } catch (Exception e) {
                    LOG.warnf("Failed to deserialize result for job %s: %s", jobId, e.getMessage());
                }
            }
        }
        return status;
    }

    /**
     * Loose-typed job status — used by manager taskTypes whose result shapes
     * (ManagerGoldenReview, SuggestionResult, Peer360Digest) don't
     * fit the strict {@link AiReviewResponse} bound on {@link #getAnalysisStatus}.
     *
     * <p>Returns a {@link Map} with {@code jobId, status, result?, errorMessage?}.
     * The {@code result} is the raw deserialised JSON tree — frontend casts to
     * the right shape based on which {@code startManager*} call produced the job.
     *
     * <p>This sidesteps the typing mismatch where binding a manager result JSON
     * (e.g. {@code {"missingAreas":[],...}}) into {@link AiReviewResponse}
     * silently drops the unknown fields and returns an empty-suggestions
     * payload to the frontend.
     */
    public Map<String, Object> getJobStatus(String jobId) {
        AiReviewStatusResponse raw = aiJobRepository.get(jobId);
        if (raw == null) return null;

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("jobId", raw.getJobId());
        body.put("status", raw.getStatus() == null ? null : raw.getStatus().name());
        if (raw.getErrorMessage() != null) body.put("errorMessage", raw.getErrorMessage());

        if (raw.getStatus() == AiReviewJobStatus.COMPLETED) {
            String resultJson = aiJobRepository.getResultJson(jobId);
            if (resultJson != null) {
                try {
                    body.put("result", mapper.readValue(resultJson, Object.class));
                } catch (Exception e) {
                    LOG.warnf("Failed to deserialize result for job %s: %s", jobId, e.getMessage());
                }
            }
        }
        // Streaming preview: surface partialText for MANAGER_GOLDEN / PEER_360_DIGEST
        // / any worker task whose ManagerGuidanceService path routes through
        // callStreaming. The worker writes partialText every ~500ms during
        // PROCESSING; without this read the front-end poll sees no streaming
        // even though the bytes are in DDB. Mirrors getInsightsStatus's behaviour.
        if (raw.getStatus() == AiReviewJobStatus.PENDING
                || raw.getStatus() == AiReviewJobStatus.PROCESSING) {
            String partial = aiJobRepository.getPartialText(jobId);
            if (partial != null) {
                body.put("partialText", partial);
            }
        }
        return body;
    }

    /**
     * Resolve the userId that requested a manager job — its owner — from the
     * persisted request envelope, which records {@code requestedBy} for every
     * MANAGER_GOLDEN / MANAGER_QUESTION_SUGGEST / PEER_360_DIGEST task. The
     * resource layer uses this to scope {@code /manager/jobs/{jobId}} reads and
     * writes to the requesting manager (or an admin), closing the leak where any
     * manager could fetch or overwrite another's result by guessing a jobId.
     *
     * <p>Returns {@code null} when the job is absent, its request carries no
     * {@code requestedBy} (e.g. a self-review ANALYZE/INSIGHTS job — never a
     * legitimate target of the manager endpoint), or the envelope can't be
     * parsed. Callers treat {@code null} as "not owned" and deny non-admins, so
     * the gate fails closed against jobId enumeration.
     */
    public String getJobOwner(String jobId) {
        String requestJson = aiJobRepository.getRequestJson(jobId);
        if (requestJson == null) return null;
        try {
            JsonNode requestedBy = mapper.readTree(requestJson).get("requestedBy");
            if (requestedBy == null || requestedBy.isNull()) return null;
            String owner = requestedBy.asText();
            return owner.isBlank() ? null : owner;
        } catch (Exception e) {
            LOG.warnf("Could not resolve owner for job %s: %s", jobId, e.getMessage());
            return null;
        }
    }

    public AiReviewInsightsStatusResponse getInsightsStatus(String jobId) {
        AiReviewStatusResponse job = aiJobRepository.get(jobId);
        if (job == null) return null;

        AiReviewInsightsStatusResponse status = new AiReviewInsightsStatusResponse();
        status.setJobId(job.getJobId());
        status.setStatus(job.getStatus());
        status.setErrorMessage(job.getErrorMessage());

        if (status.getStatus() == AiReviewJobStatus.COMPLETED) {
            String resultJson = aiJobRepository.getResultJson(jobId);
            if (resultJson != null) {
                try {
                    status.setResult(mapper.readValue(resultJson, AiReviewInsightsResponse.class));
                } catch (Exception e) {
                    LOG.warnf("Failed to deserialize insights result for job %s: %s", jobId, e.getMessage());
                }
            }
        }

        // Streaming preview: surface partialText only while the job is still
        // streaming. Once COMPLETED, the structured `result` above is the
        // source of truth and the frontend should prefer it. We still read the
        // field on COMPLETED rows (best-effort; field may be absent) so a
        // racing poll that arrives in the same millisecond as the COMPLETED
        // write can still render something useful — but the frontend's render
        // logic treats `result` as authoritative.
        if (status.getStatus() == AiReviewJobStatus.PENDING
                || status.getStatus() == AiReviewJobStatus.PROCESSING) {
            String partial = aiJobRepository.getPartialText(jobId);
            if (partial != null) {
                status.setPartialText(partial);
            }
        }
        return status;
    }

    /**
     * Persist a manager-edited AI Narrative back onto the AiJobs row.
     * Validates that the body parses as a {@link com.catalystflow.ai.model.ManagerGoldenReview}
     * with at least the required shape (citation referential integrity still
     * preserved or scrubbed), then overwrites resultJson via
     * {@link AiJobRepository#updateResultJson}. The row's telemetry (tokenIn /
     * tokenOut / modelUsed) stays intact — those reflect the original Gemini call.
     *
     * <p>Returns the persisted ManagerGoldenReview so the frontend can re-render
     * from the canonical server-side state. Throws BadRequestException on shape
     * failure (caller maps to 400). Throws {@link JobNotCompletedException} if
     * the row isn't COMPLETED (caller maps to 409).
     */
    public com.catalystflow.ai.model.ManagerGoldenReview updateManagerGoldenResult(
            String jobId, com.catalystflow.ai.model.ManagerGoldenReview body) {
        if (body == null) throw new BadRequestException("Missing body");
        // BUG-001: a terminal review's narrative is frozen. The edit endpoint only
        // carries the jobId, so resolve the reviewId from the job's requestJson and
        // refuse the edit if that review is COMPLETED/PUBLISHED.
        assertReviewNarrativeEditable(getJobReviewId(jobId));
        // Defensive — caller already deserialized via Jackson, but ensure the
        // round-trip preserves shape and re-serializes cleanly.
        String serialized;
        try {
            serialized = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new BadRequestException("Body could not be serialized: " + e.getMessage());
        }
        // Round-trip back through the model so any unknown fields are dropped
        // and ratings get coerced to the expected integer range. Validation
        // errors here = the manager hand-edited the structure into something
        // we can't represent; reject with a clear 400.
        com.catalystflow.ai.model.ManagerGoldenReview reparsed;
        try {
            reparsed = mapper.readValue(serialized, com.catalystflow.ai.model.ManagerGoldenReview.class);
        } catch (Exception e) {
            throw new BadRequestException("Body does not match ManagerGoldenReview shape: " + e.getMessage());
        }
        try {
            aiJobRepository.updateResultJson(jobId, mapper.writeValueAsString(reparsed));
        } catch (Exception e) {
            // Don't leak DDB internals — translate to a clean error if the
            // conditional check failed (row not COMPLETED) or anything else.
            if (e.getClass().getSimpleName().contains("ConditionalCheckFailed")) {
                throw new JobNotCompletedException("Job result can only be edited once it has COMPLETED");
            }
            throw new RuntimeException("Failed to persist edited golden review", e);
        }
        return reparsed;
    }

    // ── Disciplines ─────────────────────────────���─────────────────────────────

    public List<DisciplineInfo> getDisciplines() {
        List<DisciplineInfo> result = new ArrayList<>();
        criteriaTree.fields().forEachRemaining(entry -> {
            String discipline = entry.getKey();
            if (discipline.startsWith("_")) return;

            JsonNode disciplineNode = entry.getValue();
            JsonNode displayNames = disciplineNode.get("_displayNames");

            DisciplineInfo info = new DisciplineInfo();
            info.setDiscipline(discipline);
            info.setLevels(new ArrayList<>());

            // Only add proper seniority levels (objects with "inheritsFrom"); skip flat criteria arrays
            disciplineNode.fields().forEachRemaining(levelEntry -> {
                String key = levelEntry.getKey();
                if (key.startsWith("_")) return;
                JsonNode val = levelEntry.getValue();
                if (!val.isObject() || !val.has("inheritsFrom")) return;

                DisciplineSeniorityLevel level = new DisciplineSeniorityLevel();
                level.setKey(key);
                level.setDisplayName((displayNames != null && displayNames.has(key))
                        ? displayNames.get(key).asText()
                        : key);
                info.getLevels().add(level);
            });

            // Flat disciplines (Site Lead, Managing Consultant, etc.) have no seniority sub-levels.
            // Add a single sentinel level so the frontend can still render the discipline.
            if (info.getLevels().isEmpty()) {
                DisciplineSeniorityLevel single = new DisciplineSeniorityLevel();
                single.setKey(discipline);
                single.setDisplayName("Any Level");
                info.getLevels().add(single);
            }

            result.add(info);
        });
        return result;
    }

    // ── Analysis ─────────────────────────────────────────────────────���────────

    public AiReviewResponse analyze(AiReviewRequest request) {
        return analyzeWithUsage(request).response();
    }

    /**
     * Same as {@link #analyze(AiReviewRequest)} but returns the Gemini
     * token-usage metadata alongside the response so the worker can persist
     * it to AiJobs. Called from {@link #runJobInProcess(String)} — the public
     * {@code analyze} is kept as a thin delegator so existing tests / callers
     * that don't care about telemetry compile unchanged.
     */
    AnalyzeResult analyzeWithUsage(AiReviewRequest request) {
        if (mockMode) {
            LOG.info("ai-review.mock=true — returning mock response");
            return new AnalyzeResult(buildMockResponse(), null, null, null, null);
        }

        List<String> techCriteria = stackCriteria(request.getDiscipline(), request.getSeniority(), "technology");
        List<String> consultingCriteria = stackCriteria(request.getDiscipline(), request.getSeniority(), "consulting");
        String styleReference = loadStyleReference(request.getDiscipline());
        String reviewDraft = joinQuestions(request.getQuestions());
        if (reviewDraft.isEmpty()) {
            throw new BadRequestException("'questions' must have at least one non-empty text answer");
        }

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(request, techCriteria, consultingCriteria, styleReference, reviewDraft);

        try {
            // RS-5: Gemini server-side responseSchema enforces the
            // suggestions[] array shape + verdict enum (STRONG / NEEDS_IMPROVEMENT)
            // at decode time. parseResponse no longer strips markdown fences or
            // hunts for the first '{' — schema-enforced output is guaranteed
            // raw JSON. normalizeResponse keeps the business-rule cleanup
            // (cap STRONG to 1 suggestion, default missing fields).
            //
            // CC-4c: opt-in caching of the per-(discipline, seniority) prefix
            // (PERFORMANCE CRITERIA + QUALITY SIGNALS + STYLE GUIDANCE).
            // Default OFF; flip via GEMINI_CACHE_ENABLED_ANALYZE=true.
            // Single-shot model resolution per call. Both CacheRequest.model
            // and GeminiRequest.model MUST agree — they're the same string,
            // resolved once here so they can't drift.
            String analyzeModel = modelResolver.modelFor(TaskMetadata.TASK_ANALYZE);
            GeminiRequest request0;
            if (analyzeCacheEnabled) {
                String cacheablePrefix = buildAnalyzeCacheablePrefix(
                        request.getDiscipline(), request.getSeniority(),
                        techCriteria, consultingCriteria, styleReference);
                String dynamicUserPrompt = buildAnalyzeDynamicPart(request, reviewDraft);
                CacheRequest cacheReq = new CacheRequest(
                        TaskMetadata.TASK_ANALYZE,
                        TaskMetadata.PROMPT_VERSION_ANALYZE,
                        request.getDiscipline(), request.getSeniority(),
                        systemPrompt, cacheablePrefix,
                        analyzeModel,
                        3600L);  // §D5: ANALYZE uses 1h TTL — repeats during an employee drafting session
                request0 = GeminiRequest.of("analyze", systemPrompt,
                                dynamicUserPrompt, MAX_OUTPUT_TOKENS, THINKING_BUDGET_ANALYZE, analyzeModel)
                        .withResponseSchema(com.catalystflow.ai.schema.ResponseSchemas.schemaForAnalyze())
                        .withCacheRequest(cacheReq, userPrompt);
            } else {
                request0 = GeminiRequest.of("analyze", systemPrompt,
                                userPrompt, MAX_OUTPUT_TOKENS, THINKING_BUDGET_ANALYZE, analyzeModel)
                        .withResponseSchema(com.catalystflow.ai.schema.ResponseSchemas.schemaForAnalyze());
            }
            GeminiResult llm = geminiClient.call(request0);
            AiReviewResponse parsed;
            try {
                parsed = parseResponse(llm.text());
            } catch (WebApplicationException parseEx) {
                // Re-throw with Gemini telemetry attached. Without this wrap,
                // the FAILED row in runJobInProcess would lose tokenIn /
                // tokenOut / modelUsed / cacheHit / cachedTokens even though the
                // Gemini call succeeded (200 OK) — we DID spend the tokens
                // (including cached ones). The outer provider-fallback catch
                // still fires because GeminiTelemetryException IS-A
                // WebApplicationException.
                //
                // CC-5 follow-up: 7-arg constructor includes cache attribution.
                // Pre-fix the 5-arg ctor dropped llm.cacheHit/cachedTokens and
                // every parse-after-200 failure on a cached call lost cost
                // attribution for the cached prefix tokens.
                throw new GeminiTelemetryException(
                        parseEx.getMessage(),
                        Response.Status.fromStatusCode(parseEx.getResponse().getStatus()),
                        llm.tokenIn(), llm.tokenOut(), llm.modelUsed(),
                        llm.cacheHit(), llm.cachedTokens());
            }
            return new AnalyzeResult(parsed, llm.tokenIn(), llm.tokenOut(),
                    llm.cacheHit(), llm.cachedTokens());
        } catch (WebApplicationException e) {
            if (!providerFallbackEnabled) {
                throw e;
            }
            LOG.warnf("Gemini unavailable; returning local review suggestions fallback: %s", e.getMessage());
            // Recover Gemini telemetry when the failure happened AFTER a 200 OK
            // call (parse-after-200 path wraps as GeminiTelemetryException). The
            // job row is COMPLETED with the local stub content, but the tokens
            // were really spent — cost attribution must reflect that. Pure
            // network/upstream failures throw plain WebApplicationException and
            // have no telemetry to recover, so all four fields stay null in
            // that branch.
            //
            // CC-5 follow-up: also recover cacheHit + cachedTokens. The reviewer
            // caught my earlier comment claiming "we didn't get there" — wrong;
            // we DID call Gemini (200 OK before parse failed) so cached prefix
            // tokens were genuinely consumed. The COMPLETED-with-fallback row
            // must record that for accurate cache-cost queries.
            Integer tokenIn = null;
            Integer tokenOut = null;
            Boolean cacheHit = null;
            Integer cachedTokens = null;
            if (e instanceof GeminiTelemetryException gte) {
                tokenIn = gte.tokenIn;
                tokenOut = gte.tokenOut;
                cacheHit = gte.cacheHit;
                cachedTokens = gte.cachedTokens;
            }
            return new AnalyzeResult(
                    buildProviderFallbackResponse(request.getQuestions(), reviewDraft),
                    tokenIn, tokenOut, cacheHit, cachedTokens);
        }
    }

    public AiReviewInsightsResponse generateInsights(AiReviewInsightsRequest request) {
        return generateInsightsWithUsage(request, null).response();
    }

    /**
     * Same contract as {@link #analyzeWithUsage} — see that javadoc.
     *
     * <p>{@code jobId} is optional. When non-null, the worker is running this
     * as part of an SQS-driven job and the Gemini call routes through the
     * streaming endpoint so the in-flight buffer can be persisted to the
     * AiJobs row's {@code partialText} field for live UI preview. When null,
     * the legacy non-streaming path is used (sync REST handlers, tests).
     */
    InsightsResult generateInsightsWithUsage(AiReviewInsightsRequest request, String jobId) {
        if (mockMode) {
            LOG.info("ai-review.mock=true — returning mock insights response");
            AiReviewInsightsResponse mock = new AiReviewInsightsResponse();
            mock.setSummary("• Core Impact: Successfully delivered key outcomes.");
            mock.setGoldenMirror("This is a mock golden standard review version.");
            return new InsightsResult(mock, null, null, null, null);
        }

        if (!hasMeaningfulAnswer(request.getQuestions())) {
            throw new BadRequestException("'questions' must have at least one non-empty text answer");
        }

        List<String> techCriteria = criteriaForLevel(request.getDiscipline(), request.getSeniority(), "technology");
        List<String> consultingCriteria = criteriaForLevel(request.getDiscipline(), request.getSeniority(), "consulting");
        String styleSnippet = loadStyleReference(request.getDiscipline());

        String systemPrompt = buildInsightsSystemPrompt();
        String userPrompt = buildInsightsUserPrompt(request, techCriteria, consultingCriteria, styleSnippet);

        // RS-6: Gemini server-side responseSchema enforces summary +
        // goldenMirror (required strings) and the source="self" enum on every
        // citation entry. Post-RS-6 the coerceStringField safety net is gone
        // (per plan AC-6.3) — array-shape drift now FAILS parsing rather than
        // being silently coerced.
        //
        // CC-4c: opt-in caching of the per-(discipline, seniority) prefix
        // (PERFORMANCE CRITERIA + WRITING STYLE REFERENCE). Default OFF;
        // flip via GEMINI_CACHE_ENABLED_INSIGHTS=true.
        // Same single-shot resolution pattern as ANALYZE — CacheRequest.model
        // and GeminiRequest.model both pull from one resolution per call.
        String insightsModel = modelResolver.modelFor(TaskMetadata.TASK_INSIGHTS);
        GeminiRequest request0;
        if (insightsCacheEnabled) {
            String cacheablePrefix = buildInsightsCacheablePrefix(
                    request.getDiscipline(), request.getSeniority(),
                    techCriteria, consultingCriteria, styleSnippet);
            String dynamicUserPrompt = buildInsightsDynamicPart(request);
            CacheRequest cacheReq = new CacheRequest(
                    TaskMetadata.TASK_INSIGHTS,
                    TaskMetadata.PROMPT_VERSION_INSIGHTS,
                    request.getDiscipline(), request.getSeniority(),
                    systemPrompt, cacheablePrefix,
                    insightsModel,
                    1800L);  // §D5: INSIGHTS uses 30 min TTL — one-shot per submit, managers re-fire on failure
            request0 = GeminiRequest.of("insights", systemPrompt,
                            dynamicUserPrompt, INSIGHTS_MAX_OUTPUT_TOKENS, THINKING_BUDGET_INSIGHTS, insightsModel)
                    .withResponseSchema(com.catalystflow.ai.schema.ResponseSchemas.schemaForInsights())
                    .withCacheRequest(cacheReq, userPrompt);
        } else {
            request0 = GeminiRequest.of("insights", systemPrompt,
                            userPrompt, INSIGHTS_MAX_OUTPUT_TOKENS, THINKING_BUDGET_INSIGHTS, insightsModel)
                    .withResponseSchema(com.catalystflow.ai.schema.ResponseSchemas.schemaForInsights());
        }
        // jobId != null → SQS-driven worker path. Stream Gemini output and
        // persist incremental snapshots to AiJobs.partialText so the manager
        // UI can render text as it arrives (~3s to first paint vs ~55s
        // spinner-only). jobId == null → sync / test path, use the
        // non-streaming call() for backward compatibility.
        GeminiResult llm;
        if (jobId != null) {
            final String streamJobId = jobId;
            llm = geminiClient.callStreaming(request0, snapshot -> {
                // chunkHandler is best-effort (updatePartialText swallows
                // ConditionalCheckFailedException + DDB outages internally).
                // Failure here MUST NOT abort the Gemini call.
                aiJobRepository.updatePartialText(streamJobId, snapshot);
            });
        } else {
            llm = geminiClient.call(request0);
        }
        AiReviewInsightsResponse parsed;
        try {
            parsed = parseInsightsResponse(llm.text());
        } catch (WebApplicationException parseEx) {
            // Mirror of the analyzeWithUsage wrap: a 200 OK Gemini call that
            // then fails parsing must surface its token telemetry onto the
            // FAILED job row. INSIGHTS has no provider fallback so the
            // exception goes straight to runJobInProcess which hits the
            // catch (GeminiTelemetryException e) branch and persists
            // tokenIn / tokenOut / modelUsed / cacheHit / cachedTokens via
            // the 9-arg updateFailed.
            //
            // CC-5 follow-up: 7-arg constructor includes cache attribution.
            throw new GeminiTelemetryException(
                    parseEx.getMessage(),
                    Response.Status.fromStatusCode(parseEx.getResponse().getStatus()),
                    llm.tokenIn(), llm.tokenOut(), llm.modelUsed(),
                    llm.cacheHit(), llm.cachedTokens());
        }
        return new InsightsResult(parsed, llm.tokenIn(), llm.tokenOut(),
                llm.cacheHit(), llm.cachedTokens());
    }

    private boolean hasMeaningfulAnswer(List<AiReviewQuestionAnswer> questions) {
        if (questions == null) return false;
        return questions.stream().anyMatch(q -> q.getAnswer() != null && !q.getAnswer().isBlank());
    }

    private String joinQuestions(List<AiReviewQuestionAnswer> questions) {
        StringBuilder sb = new StringBuilder();
        for (AiReviewQuestionAnswer qa : questions) {
            if (qa.getAnswer() == null || qa.getAnswer().isBlank()) continue;
            sb.append("questionId: ").append(qa.getQuestionId() != null ? qa.getQuestionId() : "").append("\n");
            sb.append("Question: ").append(qa.getQuestionText() != null ? qa.getQuestionText() : "").append("\n");
            sb.append("Answer: ").append(qa.getAnswer()).append("\n\n");
        }
        return sb.toString().trim();
    }

    private AiReviewResponse buildMockResponse() {
        AiReviewQuestionFeedback improve = new AiReviewQuestionFeedback();
        improve.setQuestionId("q1");
        improve.setQuestionText("Describe your technical contributions.");
        improve.setVerdict(AiReviewQuestionFeedback.VerdictEnum.NEEDS_IMPROVEMENT);
        improve.setSuggestions(List.of(
                "Add one concrete project example and your specific action.",
                "Include a measurable outcome and who benefited."
        ));

        AiReviewQuestionFeedback strong = new AiReviewQuestionFeedback();
        strong.setQuestionId("q2");
        strong.setQuestionText("Describe your stakeholder collaboration.");
        strong.setVerdict(AiReviewQuestionFeedback.VerdictEnum.STRONG);
        strong.setSuggestions(List.of());

        AiReviewResponse response = new AiReviewResponse();
        response.setSuggestions(List.of(improve, strong));
        return response;
    }

    private AiReviewResponse buildProviderFallbackResponse(
            List<AiReviewQuestionAnswer> questions,
            String reviewDraft
    ) {
        AiReviewResponse response = new AiReviewResponse();
        List<AiReviewQuestionFeedback> suggestions = new ArrayList<>();
        for (AiReviewQuestionAnswer qa : questions) {
            if (qa.getAnswer() == null || qa.getAnswer().isBlank()) continue;
            AiReviewQuestionFeedback feedback = new AiReviewQuestionFeedback();
            feedback.setQuestionId(qa.getQuestionId());
            feedback.setQuestionText(qa.getQuestionText() != null ? qa.getQuestionText() : "");
            feedback.setVerdict(AiReviewQuestionFeedback.VerdictEnum.NEEDS_IMPROVEMENT);
            feedback.setSuggestions(List.of(
                    "Add one concrete action and one measurable outcome for this answer.",
                    "Reference project, stakeholder, or technology specifics to strengthen evidence."
            ));
            suggestions.add(feedback);
            if (suggestions.size() >= 5) break;
        }
        response.setSuggestions(suggestions);
        return response;
    }

    // ── Criteria stacking ────────────────────────────���────────────────────────

    List<String> stackCriteria(String discipline, String seniority, String category) {
        JsonNode disciplineNode = criteriaTree.get(discipline);
        if (disciplineNode == null) throw new BadRequestException("Unknown discipline: " + discipline);

        JsonNode seniorityNode = disciplineNode.get(seniority);
        if (seniorityNode == null) {
            JsonNode directCat = disciplineNode.get(category);
            if (directCat == null && "technology".equals(category)) directCat = disciplineNode.get("coreSkills");
            if (directCat != null && directCat.isArray()) {
                List<String> result = new ArrayList<>();
                directCat.forEach(c -> result.add(c.asText()));
                return result;
            }
            throw new BadRequestException("Unknown seniority: " + seniority);
        }

        List<String> levels = new ArrayList<>();
        JsonNode inheritsFrom = seniorityNode.get("inheritsFrom");
        if (inheritsFrom != null) inheritsFrom.forEach(n -> levels.add(n.asText()));
        levels.add(seniority);

        List<String> result = new ArrayList<>();
        for (String level : levels) {
            JsonNode levelNode = disciplineNode.get(level);
            if (levelNode == null) continue;
            JsonNode categoryNode = levelNode.get(category);
            if (categoryNode == null && "technology".equals(category)) categoryNode = levelNode.get("coreSkills");
            if (categoryNode != null) categoryNode.forEach(c -> result.add(c.asText()));
        }
        return result;
    }

    private List<String> criteriaForLevel(String discipline, String seniority, String category) {
        JsonNode disciplineNode = criteriaTree.get(discipline);
        if (disciplineNode == null) throw new BadRequestException("Unknown discipline: " + discipline);
        JsonNode seniorityNode = disciplineNode.get(seniority);
        if (seniorityNode == null) throw new BadRequestException("Unknown seniority: " + seniority);
        JsonNode categoryNode = seniorityNode.get(category);
        if (categoryNode == null) categoryNode = seniorityNode.get("coreSkills");
        List<String> result = new ArrayList<>();
        if (categoryNode != null) categoryNode.forEach(c -> result.add(c.asText()));
        return result;
    }

    private String loadStyleReference(String discipline) {
        JsonNode style = styleReferences.get(discipline);
        if (style == null || style.asText().isBlank()) style = styleReferences.get("general");
        return style == null ? "" : style.asText();
    }

    // ── Prompt building ───────────────────────────────────────────────────────

    private String buildSystemPrompt() {
        // Slimmed in §2.5 (post-RS): the JSON-shape example and the
        // "Return ONLY valid JSON" boilerplate are gone — responseSchema +
        // responseMimeType=application/json enforce shape at decode time.
        // What stays is BEHAVIORAL guidance the schema cannot express:
        // verdict rubric, voice/wording rules, per-question independence.
        return """
                You generate performance review suggestions for a consulting firm.
                Analyze the employee's self-appraisal draft against their performance criteria and return structured feedback.
                Review ALL provided criteria, then return only the highest-value feedback.

                Verdict rubric (lenient):
                - Default to STRONG for answers that are relevant and contain any meaningful specifics.
                - Mark NEEDS_IMPROVEMENT only when the answer is clearly off-topic, extremely vague, or too short to evaluate (for example, one brief generic sentence with no concrete detail).
                - Do not require all dimensions (action, technical detail, impact, stakeholders) to be present.
                - If the answer includes at least one concrete action OR one concrete outcome, prefer STRONG.
                - Avoid improvement suggestions that only request minor wording polish when evidence is already sufficient.

                Rules:
                - Evaluate each question independently and return one object per input questionId.
                - If verdict is STRONG, return EXACTLY ONE short suggestion (one sentence,
                  under 25 words) that names the specific thing the employee did well
                  — e.g. "Strong stakeholder example with a clear before/after outcome."
                  This must reference what the employee actually wrote; it must not be
                  a generic compliment.
                - If verdict is NEEDS_IMPROVEMENT, return 1-3 concise actionable suggestions.
                - Suggestions must reference what the employee actually wrote.
                - Keep language simple and direct. For NEEDS_IMPROVEMENT prefer wording
                  like "Try adding...", "Mention...", or "Clarify...". For STRONG prefer
                  wording like "Strong...", "Good use of...", or "Effective...".
                """;
    }

    private String buildInsightsSystemPrompt() {
        return """
                ── ROLE ─────────────────────────────────────────────────────
                You are a senior performance-review writer at a consulting firm
                (think: Practice Lead with a decade of writing year-end reviews
                for engineers and consultants). Your output is read by busy
                managers who need to draft their own assessment AND by HR / admin
                staff during calibration. They will be SKEPTICAL of generic AI
                prose; they will look for specific evidence and trust prose that
                reads like a real reviewer wrote it.

                ── INPUT ────────────────────────────────────────────────────
                You receive an employee's self-appraisal answers (free text + a
                few rating questions). Each answer is paired with its question
                text and questionId. Treat the employee's own words as the
                ONLY admissible evidence.

                ════════════════════════════════════════════════════════════
                `goldenMirror` — the primary artifact. Be deliberate.
                ════════════════════════════════════════════════════════════

                LENGTH
                - Each section: 2–3 paragraphs of flowing prose, ~180–250 words.
                - Total goldenMirror: ~550–750 words.
                - Bullet points are forbidden inside paragraphs. Prose only.
                - If a section has thin evidence, write less but DO NOT pad.

                SECTION STRUCTURE (exact headers, exact order):
                  TECHNOLOGY / CORE SKILLS
                  CONSULTING SKILLS
                  CATALYSTFLOW VALUES CONSISTENTLY DEMONSTRATED

                Each section opens with an ASSESSMENT SENTENCE that names the
                employee's level of effectiveness on this dimension this cycle,
                then PROVES it across the rest of the section using their own
                evidence.

                VOICE — what good looks like
                - Third person, named subject. Don't say "the employee"; say
                  the actual first name (extract it from any "I, NAME, ..." style
                  signature, or from named stakeholders the employee mentions).
                  If you genuinely cannot determine the name, fall back to
                  "the employee".
                - Past tense for completed work, present tense for ongoing.
                - Sentence rhythm: vary length. Don't write five 18-word
                  sentences in a row. Open with a punchy claim, develop with
                  evidence, close with consequence or what changed.
                - Quote the employee's vivid phrases inline, in quotes, where
                  they appear in their answer — e.g. ... noting they "had to
                  navigate the FX feed quirks" before the cutover.
                - Acknowledge tensions or growth areas mid-strength sections
                  if the employee surfaced them. Reviews that read as 100%
                  praise read fake. If the employee wrote a self-aware gap,
                  reflect it.

                VOICE — what to avoid (these are AI tells; do not produce them)
                - "Demonstrates strong X and effective Y." (vague pair-up)
                - "Showcased excellent communication skills." (no proof)
                - "Brings a wealth of experience to the team." (filler)
                - Three-adjective combos: "thoughtful, collaborative, and
                  strategic". Use one specific verb instead.
                - "It is worth noting that..." / "Notably,..." (LLM throat-clearing)
                - Closing sentences that restate the section's thesis without
                  adding evidence.
                - Phrasings like "a true asset to the organization" — they
                  belong to LinkedIn endorsement screenshots, not reviews.

                EVIDENCE INTEGRATION
                - For each claim, integrate the actual source — named project,
                  named client, named technology, named metric, named
                  stakeholder, named time period — directly into the sentence.
                - Numbers earn their place. If the employee wrote "cut nightly
                  close from 4 hours to 35 minutes", that phrase or substring
                  belongs in the prose. Don't round, don't paraphrase, don't
                  add metrics they didn't write.
                - If the employee mentioned a challenge they handled,
                  reconstruct the situation/action/outcome arc compactly.
                  Don't skip the "what changed" sentence.

                NO-FABRICATION RULE
                - Every fact must trace to a sentence the employee actually
                  wrote. If a topic the section header demands (e.g. consulting
                  skills) has no supporting content in the answers, the entire
                  section body must be exactly: [Information not provided]
                  (single line, no quotes, no headers below it).
                - Do not infer skills from job titles or generalities.
                - Do not invent client names, projects, technologies, metrics.

                ════════════════════════════════════════════════════════════
                Citations — dense, specific, verbatim
                ════════════════════════════════════════════════════════════

                EVERY SUBSTANTIVE CLAIM GETS A CITATION. Aim for 4–8 [cN]
                tokens per non-empty section. A 3-paragraph section with only
                one citation is wrong.

                Token placement:
                - At the END of the clause/sentence containing the claim,
                  immediately after the closing punctuation: ... before the
                  cutover [c1].
                - One claim, one token. Don't stack tokens — write the second
                  claim as its own sentence.

                Numbering is global across goldenMirror (c1, c2, c3 cross
                sections — don't restart per section).

                Citation semantics (the schema enforces the entry shape;
                what it can't enforce is BEHAVIOR):
                - source is always "self" — INSIGHTS sees only the employee's answers.
                - reviewId echoes the input reviewId if present, else empty string.
                - questionId is the id of the answer the quote came from.
                - quote is a verbatim substring of that answer, ≤ 15 words.

                QUOTE FIELD RULES (these matter — frontend uses quote text to
                jump-and-highlight the source span)
                - Verbatim substring of the actual answer text. No paraphrase.
                  No concatenation of spans. No invented punctuation.
                - Pick the MOST SPECIFIC substring that anchors the claim. If
                  the employee wrote a long paragraph supporting one claim,
                  pick the single most diagnostic 8–15 word phrase.
                - Keep punctuation as-is. If the substring ends mid-sentence,
                  end mid-sentence in the quote too.

                ════════════════════════════════════════════════════════════
                `summary` — secondary, for compatibility
                ════════════════════════════════════════════════════════════
                - 3–5 bullet points using a leading "- " (dash + space).
                - High-level only; the detail lives in goldenMirror.
                - No citation tokens. No section headers.
                - Each bullet ≤ 18 words.
                """;
    }

    /**
     * Build the cacheable (static-per-{@code (discipline, seniority)}) portion
     * of the ANALYZE user prompt: PERFORMANCE CRITERIA + tech criteria +
     * consulting criteria + QUALITY SIGNALS + STYLE GUIDANCE.
     *
     * <p>Bytes are stable across every ANALYZE call within the same
     * {@code (discipline, seniority)} drafting session — that's the high-reuse
     * pattern CC-4c targets. The dynamic portion (EMPLOYEE DRAFT block with
     * the actual review answers) lives in {@link #buildAnalyzeDynamicPart}.
     *
     * <p>When the cache flag is OFF, both halves are concatenated by
     * {@link #buildUserPrompt} into a single user prompt in the
     * <b>prefix-then-dynamic</b> byte order — identical to what
     * {@code buildUserPrompt} produced before CC-4c. Cache-OFF is the
     * production default; this byte-identity is the safety contract for
     * the rollout.
     */
    String buildAnalyzeCacheablePrefix(String discipline,
                                       String seniority,
                                       List<String> techCriteria,
                                       List<String> consultingCriteria,
                                       String styleReference) {
        StringBuilder sb = new StringBuilder();

        sb.append("PERFORMANCE CRITERIA - ").append(seniority).append(" | ").append(discipline).append("\n");
        sb.append("Use the criteria as context when evaluating each answer.\n\n");

        sb.append("Technology / Core Skills:\n");
        for (int i = 0; i < techCriteria.size(); i++) {
            sb.append(i + 1).append(". ").append(techCriteria.get(i)).append("\n");
        }

        sb.append("\nConsulting Skills:\n");
        for (int i = 0; i < consultingCriteria.size(); i++) {
            sb.append(i + 1).append(". ").append(consultingCriteria.get(i)).append("\n");
        }

        sb.append("\n---\n");
        sb.append("QUALITY SIGNALS\n");
        sb.append("For answers that seem weak, focus suggestions on missing specifics such as:\n");
        sb.append("- Named specific projects, technologies, tools, or clients (signal: specificity)\n");
        sb.append("- A stated outcome or measurable result, not just an action taken (signal: outcome)\n");
        sb.append("- Acknowledgement of a challenge and how it was navigated (signal: challenge-resolution)\n");
        sb.append("- Reference to specific stakeholders by role (signal: stakeholder)\n");
        sb.append("- Before/after context showing what changed or improved (signal: before-after)\n");
        sb.append("Do not ask for all of these at once; prioritize the most impactful 1-3 suggestions only when needed.\n");

        if (!styleReference.isEmpty()) {
            sb.append("\n---\n");
            sb.append("STYLE GUIDANCE\n");
            sb.append(styleReference).append("\n");
        }

        return sb.toString();
    }

    /**
     * Build the per-call (dynamic) portion of the ANALYZE user prompt — the
     * EMPLOYEE DRAFT block with the actual review answers. Seniority +
     * Discipline are repeated here as part of the dynamic byte stream for
     * Gemini's context (they're also in the cacheable prefix's header).
     *
     * <p>When cache-ON: this is sent as the per-call userPrompt; Gemini
     * prepends the cached prefix. When cache-OFF: concatenated by
     * {@link #buildUserPrompt} after the cacheable prefix.
     */
    String buildAnalyzeDynamicPart(AiReviewRequest req, String reviewDraft) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n---\n");
        sb.append("EMPLOYEE DRAFT\n");
        sb.append("Seniority: ").append(req.getSeniority()).append("\n");
        sb.append("Discipline: ").append(req.getDiscipline()).append("\n\n");
        sb.append(reviewDraft);
        return sb.toString();
    }

    /**
     * Cache-OFF combined ANALYZE prompt. MUST be byte-identical to pre-CC-4c
     * — production default is cache-OFF, so this concatenation defines the
     * legacy byte order. Order: prefix-then-dynamic (the order
     * buildUserPrompt produced before CC-4c).
     */
    private String buildUserPrompt(AiReviewRequest req,
                                   List<String> techCriteria,
                                   List<String> consultingCriteria,
                                   String styleReference,
                                   String reviewDraft) {
        return buildAnalyzeCacheablePrefix(req.getDiscipline(), req.getSeniority(),
                       techCriteria, consultingCriteria, styleReference)
                + buildAnalyzeDynamicPart(req, reviewDraft);
    }

    /**
     * Build the cacheable (static-per-{@code (discipline, seniority)}) portion
     * of the INSIGHTS user prompt: PERFORMANCE CRITERIA + tech criteria +
     * consulting criteria + WRITING STYLE REFERENCE. Stable across every
     * INSIGHTS call within the same role bucket.
     *
     * <p>Dynamic portion (the employee's submitted questions + answers)
     * lives in {@link #buildInsightsDynamicPart}. Cache-OFF concatenation
     * is prefix-then-dynamic, byte-identical to pre-CC-4c.
     */
    String buildInsightsCacheablePrefix(String discipline,
                                        String seniority,
                                        List<String> techCriteria,
                                        List<String> consultingCriteria,
                                        String styleSnippet) {
        StringBuilder sb = new StringBuilder();
        sb.append("PERFORMANCE CRITERIA — ").append(seniority).append(" | ").append(discipline).append("\n\n");
        sb.append("Technology Criteria:\n").append(String.join("\n", techCriteria)).append("\n\n");
        sb.append("Consulting Criteria:\n").append(String.join("\n", consultingCriteria)).append("\n\n");
        sb.append("WRITING STYLE REFERENCE:\n").append(styleSnippet).append("\n\n");
        return sb.toString();
    }

    /**
     * Build the per-call (dynamic) portion of the INSIGHTS user prompt — the
     * employee's submitted questions and answers. Per-call data; never cached.
     */
    String buildInsightsDynamicPart(AiReviewInsightsRequest req) {
        StringBuilder sb = new StringBuilder();
        for (AiReviewQuestionAnswer qa : req.getQuestions()) {
            String questionText = qa.getQuestionText() != null && !qa.getQuestionText().isBlank()
                    ? qa.getQuestionText()
                    : qa.getQuestionId();
            String answer = qa.getAnswer() != null ? qa.getAnswer() : "";
            // Include questionId so Gemini can populate the citations map
            // accurately (each citation entry references the source question
            // by id, not by question text).
            String qid = qa.getQuestionId() != null ? qa.getQuestionId() : "";
            sb.append("Q (").append(qid).append("): ").append(questionText)
              .append("\nA: ").append(answer).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * Cache-OFF combined INSIGHTS prompt. MUST be byte-identical to pre-CC-4c
     * — production default is cache-OFF, so this concatenation defines the
     * legacy byte order: prefix-then-dynamic.
     */
    private String buildInsightsUserPrompt(AiReviewInsightsRequest req,
                                           List<String> techCriteria,
                                           List<String> consultingCriteria,
                                           String styleSnippet) {
        return buildInsightsCacheablePrefix(req.getDiscipline(), req.getSeniority(),
                       techCriteria, consultingCriteria, styleSnippet)
                + buildInsightsDynamicPart(req);
    }

    // ── Gemini API call ──────────────────────────────��────────────────────────
    // (HTTP + retry + finishReason handling moved to GeminiClient in RS-1.
    //  Constants below are forwarding declarations kept for backward source
    //  compatibility; AiWorkerHandler still references AiReviewService.MAX_429_RETRIES
    //  on this branch.)

    /**
     * @deprecated since RS-1 — use {@link GeminiClient#MAX_429_RETRIES} directly.
     *             Forwarding constant retained because {@code AiWorkerHandler}
     *             on this branch still references the AiReviewService-scoped
     *             constant; remove this once the worker is updated.
     */
    @Deprecated
    public static final int MAX_429_RETRIES = GeminiClient.MAX_429_RETRIES;

    /**
     * @deprecated since RS-1 — moved to {@link GeminiClient#parseRetryAfterMs}.
     *             Forwarding wrapper kept until {@code ManagerGuidanceService}
     *             is migrated (RS-1 Task 4); will be deleted alongside that.
     */
    @Deprecated
    static java.util.OptionalLong parseRetryAfterMs(java.net.http.HttpResponse<?> response) {
        return GeminiClient.parseRetryAfterMs(response);
    }

    // The remainder of the original callGemini / sleepQuiet / GEMINI_HTTP_TIMEOUT
    // / MAX_429_BACKOFF_MS block was deleted in RS-1 — see GeminiClient for the
    // canonical implementation.

    // ── Response parsing ──────────────────────────────────────────────────────

    /**
     * Parse Gemini's {@code ANALYZE} response into {@link AiReviewResponse}.
     *
     * <p>RS-5: the markdown-strip + brace-find heuristics are gone. Gemini's
     * responseSchema enforcement (see
     * {@link com.catalystflow.ai.schema.ResponseSchemas#schemaForAnalyze})
     * guarantees raw JSON at decode time; any markdown drift means the
     * schema enforcement has a gap, which is a real signal — let the parser
     * fail loudly so we see it in errorMessage as MALFORMED.
     *
     * <p>{@link #normalizeResponse} stays — it does business-rule capping
     * that responseSchema can't express (cap STRONG to 1 suggestion, default
     * a canned fallback when Gemini emits empty suggestions, filter blank
     * questionIds).
     */
    private AiReviewResponse parseResponse(String llmResponse) {
        try {
            return normalizeResponse(mapper.readValue(llmResponse, AiReviewResponse.class));
        } catch (Exception e) {
            throw new WebApplicationException("Invalid AI response format", Response.Status.BAD_GATEWAY);
        }
    }

    /**
     * Parse Gemini's {@code INSIGHTS} response into {@link AiReviewInsightsResponse}.
     *
     * <p>RS-6: {@code coerceStringField} (the array-vs-string drift safety net)
     * is deleted per plan AC-6.3 — Gemini's responseSchema (see
     * {@link com.catalystflow.ai.schema.ResponseSchemas#schemaForInsights})
     * enforces {@code type:"string"} on {@code summary} and {@code goldenMirror}
     * server-side. If Gemini somehow drifts back to array-shape responses, the
     * Jackson bind below fails and we surface as MALFORMED via classifyError —
     * a real signal worth investigating, not papered over silently.
     *
     * <p>{@link #validateInsightsResponse} stays — schema can require fields
     * be present and string-typed but cannot cheaply express "non-blank".
     * The markdown-strip + brace-finder heuristics stay for now (not covered
     * by plan AC-6.3); they're no-ops on schema-correct responses.
     */
    private AiReviewInsightsResponse parseInsightsResponse(String llmResponse) {
        try {
            String json = llmResponse.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
            }
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            AiReviewInsightsResponse parsed = mapper.readValue(json, AiReviewInsightsResponse.class);
            validateInsightsResponse(parsed);
            // 2026-05-25 hotfix follow-up: post-parse scrub for dangling [cN]
            // markers. Even with schema-required citations, Gemini sometimes
            // emits citations: {} while still embedding [cN] in prose. Strip
            // unmatched markers so the frontend never renders unclickable
            // numbers. The WARN log is a canary — if it ever fires in
            // production, Gemini regressed on the schema-enforced contract.
            Map<String, ?> citations = parsed.getCitations();
            Set<String> dangling = findDanglingCitations(parsed.getSummary(), parsed.getGoldenMirror(), citations);
            if (!dangling.isEmpty()) {
                int originalSummaryLen = parsed.getSummary() == null ? 0 : parsed.getSummary().length();
                int originalMirrorLen = parsed.getGoldenMirror() == null ? 0 : parsed.getGoldenMirror().length();
                LOG.warnf("INSIGHTS dangling citations scrubbed — Gemini regression canary. "
                        + "originalSummaryLen=%d originalMirrorLen=%d dangling=%s",
                        originalSummaryLen, originalMirrorLen, dangling);
                parsed.setSummary(scrubDanglingCitations(parsed.getSummary(), citations));
                parsed.setGoldenMirror(scrubDanglingCitations(parsed.getGoldenMirror(), citations));
            }
            return parsed;
        } catch (Exception e) {
            // Log the raw response (truncated) so future failures can be diagnosed
            // without needing to redeploy with debug logging. Without this we had
            // to guess at the failure shape (see 2026-05-10 incident).
            String preview = llmResponse == null ? "(null)"
                    : (llmResponse.length() > 800 ? llmResponse.substring(0, 800) + "…[truncated]" : llmResponse);
            LOG.errorf("INSIGHTS parse/validate failed: %s. Raw Gemini response: %s", e.getMessage(), preview);
            throw new WebApplicationException("Invalid AI insights response format", Response.Status.BAD_GATEWAY);
        }
    }

    // coerceStringField removed in RS-6 per plan AC-6.3. Gemini's
    // responseSchema enforces type:"string" on summary + goldenMirror
    // server-side; any drift now fails parse and surfaces as MALFORMED
    // rather than being silently coerced.

    private void validateInsightsResponse(AiReviewInsightsResponse response) {
        // Tolerant validation — only enforce non-empty fields, not exact section
        // headers. Gemini 2.5 flash in JSON mode occasionally varies casing and
        // spacing on the goldenMirror's section headers (e.g. "Technology / Core
        // Skills" vs "TECHNOLOGY / CORE SKILLS"); the previous strict
        // {@code mirror.contains("TECHNOLOGY / CORE SKILLS")} check was failing
        // for valid responses → fallback. If the goldenMirror genuinely lacks
        // a section, the frontend renders what's present rather than the entire
        // job failing — strictly better UX than silent fallback.
        if (response == null || response.getSummary() == null || response.getGoldenMirror() == null) {
            throw new IllegalArgumentException("Missing summary or goldenMirror");
        }
        String summary = response.getSummary().trim();
        String mirror = response.getGoldenMirror().trim();
        if (summary.isBlank() || mirror.isBlank()) {
            throw new IllegalArgumentException("Empty summary or goldenMirror");
        }
    }

    /**
     * Regex matching the {@code [cN]} citation token shape used by INSIGHTS,
     * MANAGER_GOLDEN and QUESTION_SUGGEST. Group 1 is the bare key (e.g.
     * {@code c1}) so callers can subtract against {@code citations.keySet()}.
     */
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[(c\\d+)\\]");

    /**
     * Return the set of {@code cN} keys referenced in either text but missing
     * from the citations map. Treats a null map as empty. Both texts may be
     * null (treated as empty). Returns an empty set when nothing needs
     * scrubbing — callers use {@code .isEmpty()} as the gate for the canary
     * WARN log so that the happy path stays log-quiet.
     */
    private static Set<String> findDanglingCitations(String summary, String mirror,
                                                     Map<String, ?> citations) {
        Set<String> referenced = new HashSet<>();
        collectCitationKeys(summary, referenced);
        collectCitationKeys(mirror, referenced);
        if (referenced.isEmpty()) {
            return Set.of();
        }
        if (citations != null && !citations.isEmpty()) {
            referenced.removeAll(citations.keySet());
        }
        return referenced;
    }

    private static void collectCitationKeys(String text, Set<String> sink) {
        if (text == null || text.isEmpty()) return;
        Matcher m = CITATION_MARKER.matcher(text);
        while (m.find()) {
            sink.add(m.group(1));
        }
    }

    /**
     * Strip any {@code [cN]} tag whose key {@code cN} is NOT present in the
     * supplied citations map (or strip all {@code [cN]} tags when the map is
     * null/empty). Cleans up the immediately-following space and collapses
     * any resulting double-space to a single space, so prose stays readable.
     *
     * <p>Package-private for unit testing — see
     * {@code AiReviewServiceCitationScrubTest}.
     *
     * @return the input text unchanged when no scrubbing is needed.
     */
    static String scrubDanglingCitations(String text, Map<String, ?> citations) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = CITATION_MARKER.matcher(text);
        if (!m.find()) return text;
        // Reset for the actual replace pass.
        m.reset();
        StringBuilder out = new StringBuilder(text.length());
        boolean scrubbed = false;
        int last = 0;
        while (m.find()) {
            String key = m.group(1);
            boolean present = citations != null && !citations.isEmpty() && citations.containsKey(key);
            if (present) {
                // Keep the tag — copy through verbatim.
                out.append(text, last, m.end());
                last = m.end();
            } else {
                int stripStart = m.start();
                int stripEnd = m.end();
                // Pull in one trailing space so we don't leave "  " mid-prose.
                if (stripEnd < text.length() && text.charAt(stripEnd) == ' ') {
                    stripEnd++;
                } else if (stripStart > 0 && text.charAt(stripStart - 1) == ' ') {
                    // No trailing space (likely end-of-sentence "[c1].") — pull
                    // in the preceding space so we don't leave " ." or trailing
                    // whitespace at the boundary.
                    // Roll back the StringBuilder by one if we've already
                    // emitted that space, otherwise just shift the strip range.
                    if (out.length() > 0 && out.charAt(out.length() - 1) == ' '
                            && (stripStart - 1) < last) {
                        out.deleteCharAt(out.length() - 1);
                    } else {
                        stripStart--;
                    }
                }
                out.append(text, last, stripStart);
                last = stripEnd;
                scrubbed = true;
            }
        }
        out.append(text, last, text.length());
        if (!scrubbed) return text;
        // Defensive: collapse any stray "  " that survived.
        String result = out.toString();
        while (result.contains("  ")) {
            result = result.replace("  ", " ");
        }
        return result;
    }

    private AiReviewResponse normalizeResponse(AiReviewResponse response) {
        AiReviewResponse normalized = new AiReviewResponse();
        List<AiReviewQuestionFeedback> suggestions = new ArrayList<>();
        if (response != null && response.getSuggestions() != null) {
            for (AiReviewQuestionFeedback item : response.getSuggestions()) {
                if (item == null || item.getQuestionId() == null || item.getQuestionId().isBlank()) continue;
                if (item.getQuestionText() == null) item.setQuestionText("");
                if (item.getVerdict() == null) item.setVerdict(AiReviewQuestionFeedback.VerdictEnum.NEEDS_IMPROVEMENT);
                if (item.getVerdict() == AiReviewQuestionFeedback.VerdictEnum.STRONG) {
                    // STRONG now carries one positive observation per question
                    // (prompt updated 2026-05-11) so the manager sees specific
                    // praise instead of identical canned text. Cap at 1 to
                    // keep the card compact; fall back to a generic positive
                    // only when Gemini omits the note (defensive — shouldn't
                    // happen with the updated prompt).
                    if (item.getSuggestions() == null || item.getSuggestions().isEmpty()) {
                        item.setSuggestions(List.of("Looks strong — clear and well-supported."));
                    } else if (item.getSuggestions().size() > 1) {
                        item.setSuggestions(item.getSuggestions().subList(0, 1));
                    }
                } else if (item.getSuggestions() == null || item.getSuggestions().isEmpty()) {
                    item.setSuggestions(List.of("Add concrete actions and measurable outcomes to strengthen this answer."));
                } else if (item.getSuggestions().size() > 3) {
                    item.setSuggestions(item.getSuggestions().subList(0, 3));
                }
                suggestions.add(item);
            }
        }
        normalized.setSuggestions(suggestions);
        return normalized;
    }

    // ── Manager guidance ──────────────────────────────────────────────────────

    public static class ReviewSnapshot {
        public String reviewId, subjectUserId, cycleId, templateId, authorUserId;
        public String overallComment, overallRating;
        public Integer version;
        public List<Answer> answers = new ArrayList<>();
    }

    public ReviewSnapshot getReviewSnapshot(String reviewId) {
        var res = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(reviewsTable)
                .key(Map.of("reviewId", AttributeValue.builder().s(reviewId).build()))
                .build());
        if (!res.hasItem() || res.item().isEmpty())
            throw new NotFoundException("Review not found: " + reviewId);
        Map<String, AttributeValue> i = res.item();
        ReviewSnapshot r = new ReviewSnapshot();
        r.reviewId = reviewId;
        r.subjectUserId = str(i, "subjectUserId");
        r.cycleId = str(i, "cycleId");
        r.templateId = str(i, "templateId");
        r.version = intVal(i, "version");
        r.overallComment = str(i, "overallComment");
        r.overallRating = str(i, "overallRating");
        String answersJson = str(i, "answers");
        if (answersJson != null && !answersJson.isBlank()) {
            try { r.answers = mapper.readValue(answersJson, new TypeReference<List<Answer>>() {}); }
            catch (Exception ignored) {}
        }
        return r;
    }

    /**
     * Pure compute helper for MANAGER_GOLDEN — composite per ADR-3. Loads the
     * manager's draft, the subject's SELF review, and every completed PEER_360
     * review for the cycle, then feeds Gemini a fenced corpus so it can emit
     * citation tokens that point back to specific source rows.
     *
     * <p>Returns the full {@link ManagerGuidanceService.GoldenResult} wrapper —
     * the worker unwraps {@code .golden} for the resultJson payload and reads
     * {@code .tokenIn / .tokenOut / .repairRetries} for AiJobs telemetry.
     */
    private ManagerGuidanceService.GoldenResult computeManagerGolden(ReviewSnapshot review) {
        return computeManagerGolden(review, null);
    }

    /**
     * Streaming-capable overload. When {@code jobId != null}, Gemini runs via
     * SSE and partialText snapshots land on AiJobs as the narrative emerges —
     * the manager watches the AI Review tab fill in live.
     */
    private ManagerGuidanceService.GoldenResult computeManagerGolden(ReviewSnapshot review, String jobId) {
        // Manager's own draft answers
        List<ManagerGuidanceService.AnswerWithText> mgrAnswers = enrichAnswers(review);
        String managerDraft = managerGuidanceService.buildReviewText(
                mgrAnswers, review.overallComment, review.overallRating);

        // Subject's SELF review (one row at most, but we query defensively)
        List<ReviewSnapshot> selfRows = queryCompletedSelf(review.cycleId, review.subjectUserId);
        ReviewSnapshot self = selfRows.isEmpty() ? null : selfRows.get(0);

        // Completed PEER_360 reviews
        List<ReviewSnapshot> peer360 = queryCompletedPeer360(review.cycleId, review.subjectUserId);

        StringBuilder corpus = new StringBuilder();
        corpus.append("=== Manager draft (reviewId=").append(review.reviewId).append(") ===\n");
        corpus.append(managerDraft.isBlank() ? "(no manager content yet)" : managerDraft).append("\n\n");

        if (self != null) {
            corpus.append("=== Employee self review (source=self, reviewId=").append(self.reviewId).append(") ===\n");
            for (var qa : enrichAnswers(self)) {
                if (qa.response != null && !qa.response.isBlank()) {
                    corpus.append("Q (")
                          .append(qa.questionId).append("): ")
                          .append(qa.questionText).append("\n");
                    corpus.append("A: ").append(qa.response).append("\n\n");
                }
            }
        }

        for (ReviewSnapshot peer : peer360) {
            corpus.append("=== Peer 360 review (source=peer_360, reviewId=").append(peer.reviewId)
                  .append(", authorUserId=").append(nz(peer.authorUserId)).append(") ===\n");
            for (var qa : enrichAnswers(peer)) {
                if (qa.response != null && !qa.response.isBlank()) {
                    corpus.append("Q (").append(qa.questionId).append("): ")
                          .append(qa.questionText).append("\n");
                    corpus.append("A: ").append(qa.response).append("\n\n");
                }
            }
        }

        return managerGuidanceService.generateGoldenReview(corpus.toString().trim(), jobId);
    }

    /** SELF review for (cycleId, subjectUserId), only if COMPLETED/PUBLISHED. */
    private List<ReviewSnapshot> queryCompletedSelf(String cycleId, String subjectUserId) {
        return queryCompletedByCycleType(cycleId, subjectUserId, "SELF");
    }

    /** Generic helper used by both SELF and PEER_360 queries above. */
    private List<ReviewSnapshot> queryCompletedByCycleType(String cycleId,
                                                           String subjectUserId,
                                                           String reviewType) {
        String skPrefix = cycleId + "#" + reviewType;
        List<ReviewSnapshot> results = new ArrayList<>();
        Map<String, AttributeValue> lastKey = null;
        do {
            software.amazon.awssdk.services.dynamodb.model.QueryRequest.Builder qb =
                    software.amazon.awssdk.services.dynamodb.model.QueryRequest.builder()
                            .tableName(reviewsTable)
                            .indexName("SubjectIndex")
                            .keyConditionExpression("#pk = :pk AND begins_with(#sk, :sk)")
                            .expressionAttributeNames(Map.of("#pk", "subjectUserId", "#sk", "cycleTypeKey"))
                            .expressionAttributeValues(Map.of(
                                    ":pk", AttributeValue.builder().s(subjectUserId).build(),
                                    ":sk", AttributeValue.builder().s(skPrefix).build()));
            if (lastKey != null) qb.exclusiveStartKey(lastKey);
            var res = dynamoDb.query(qb.build());
            for (Map<String, AttributeValue> item : res.items()) {
                String status = str(item, "status");
                if (!"COMPLETED".equals(status) && !"PUBLISHED".equals(status)) continue;
                ReviewSnapshot r = new ReviewSnapshot();
                r.reviewId = str(item, "reviewId");
                r.subjectUserId = str(item, "subjectUserId");
                r.cycleId = str(item, "cycleId");
                r.templateId = str(item, "templateId");
                r.version = intVal(item, "version");
                r.overallComment = str(item, "overallComment");
                r.overallRating = str(item, "overallRating");
                r.authorUserId = str(item, "authorUserId");
                String answersJson = str(item, "answers");
                if (answersJson != null && !answersJson.isBlank()) {
                    try { r.answers = mapper.readValue(answersJson, new TypeReference<List<Answer>>() {}); }
                    catch (Exception ignored) {}
                }
                results.add(r);
            }
            lastKey = res.hasLastEvaluatedKey() ? res.lastEvaluatedKey() : null;
        } while (lastKey != null && !lastKey.isEmpty());
        return results;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /**
     * Pure compute helper for PEER_360_DIGEST — queries the SubjectIndex GSI
     * for every completed PEER_360 row in (cycleId, subjectUserId), enriches
     * each row's answers with its template's question text, and asks Gemini
     * to cluster the corpus into themes/consensus/dissent.
     */
    private ManagerGuidanceService.DigestResult computePeer360Digest(
            String cycleId, String subjectUserId) {
        return computePeer360Digest(cycleId, subjectUserId, null);
    }

    /** Streaming-capable overload — mirror of {@link #computeManagerGolden(ReviewSnapshot, String)}. */
    private ManagerGuidanceService.DigestResult computePeer360Digest(
            String cycleId, String subjectUserId, String jobId) {
        if (cycleId == null || cycleId.isBlank())
            throw new BadRequestException("cycleId is required");
        if (subjectUserId == null || subjectUserId.isBlank())
            throw new BadRequestException("subjectUserId is required");

        List<ReviewSnapshot> peerRows = queryCompletedPeer360(cycleId, subjectUserId);
        if (peerRows.isEmpty()) {
            // No completed peer reviews yet — return an empty but valid digest
            // so the frontend can render "no peer feedback yet" without an error.
            // No Gemini call → token telemetry fields stay null.
            com.catalystflow.ai.model.Peer360Digest empty = new com.catalystflow.ai.model.Peer360Digest();
            empty.themes = List.of();
            empty.consensus = List.of();
            empty.dissent = List.of();
            empty.overallSentiment = "mixed";
            ManagerGuidanceService.DigestResult wrapper = new ManagerGuidanceService.DigestResult();
            wrapper.digest = empty;
            return wrapper;
        }

        List<ManagerGuidanceService.Peer360ReviewBlock> blocks = new ArrayList<>();
        for (ReviewSnapshot peer : peerRows) {
            ManagerGuidanceService.Peer360ReviewBlock block =
                    new ManagerGuidanceService.Peer360ReviewBlock();
            block.reviewId = peer.reviewId;
            block.answers = enrichAnswers(peer);
            blocks.add(block);
        }
        String corpus = managerGuidanceService.buildPeer360Corpus(blocks);
        return managerGuidanceService.generatePeer360Digest(corpus, jobId);
    }

    /**
     * Query SubjectIndex GSI for completed PEER_360 rows of (cycleId, subjectUserId).
     * Mirrors {@code ReviewService.queryGsi} in review-service — kept local here
     * to avoid a cross-module dependency. Loops on LastEvaluatedKey to page
     * through all results.
     */
    private List<ReviewSnapshot> queryCompletedPeer360(String cycleId, String subjectUserId) {
        return queryCompletedByCycleType(cycleId, subjectUserId, "PEER_360");
    }

    /**
     * Builds the (questionText + answer) pairs Gemini wants from a {@link
     * ReviewSnapshot}'s template + answers. Falls back to questionId when the
     * template lookup misses (defensive — never block the prompt on a missing
     * template row).
     */
    private List<ManagerGuidanceService.AnswerWithText> enrichAnswers(ReviewSnapshot review) {
        List<TemplateQuestion> questions = loadTemplateQuestions(review.templateId);
        Map<String, String> qMap = new java.util.HashMap<>();
        for (var q : questions) qMap.put(q.questionId, q.text);
        List<ManagerGuidanceService.AnswerWithText> enriched = new ArrayList<>();
        for (var a : review.answers) {
            var awt = new ManagerGuidanceService.AnswerWithText();
            awt.questionId = a.questionId;
            awt.questionText = qMap.getOrDefault(a.questionId, a.questionId);
            awt.response = a.response;
            enriched.add(awt);
        }
        return enriched;
    }

    private List<TemplateQuestion> loadTemplateQuestions(String templateId) {
        if (templateId == null) return List.of();
        try {
            var res = dynamoDb.getItem(GetItemRequest.builder()
                    .tableName(templatesTable)
                    .key(Map.of("templateId", AttributeValue.builder().s(templateId).build()))
                    .build());
            if (!res.hasItem() || res.item().isEmpty()) return List.of();
            String q = str(res.item(), "questions");
            if (q == null) return List.of();
            return mapper.readValue(q, new TypeReference<List<TemplateQuestion>>() {});
        } catch (Exception e) {
            LOG.warnf(e, "Failed to load template questions for %s", templateId);
            return List.of();
        }
    }

    private static String str(Map<String, AttributeValue> m, String k) {
        return m.containsKey(k) ? m.get(k).s() : null;
    }

    private static Integer intVal(Map<String, AttributeValue> m, String k) {
        try { return m.containsKey(k) ? Integer.valueOf(m.get(k).s()) : null; }
        catch (Exception e) { return null; }
    }
}

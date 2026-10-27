package com.catalystflow.ai.service;

import com.catalystflow.ai.model.ManagerGoldenReview;
import com.catalystflow.ai.model.Peer360Digest;
import com.catalystflow.ai.model.QuestionSuggestRequest;
import com.catalystflow.ai.model.SchemaInvalidException;
import com.catalystflow.ai.model.TemplateQuestion;
import com.catalystflow.ai.repository.AiJobRepository;
import com.catalystflow.ai.schema.ResponseSchemas;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class ManagerGuidanceService {

    private static final Logger LOG = Logger.getLogger(ManagerGuidanceService.class);

    @ConfigProperty(name = "coaching.mock", defaultValue = "false") boolean mockMode;

    /**
     * Per-task Gemini model selection. Replaces the previously-injected single
     * {@code gemini.model} string — now GOLDEN, QUESTION_SUGGEST, and DIGEST
     * can resolve to different models independently. Each {@link CacheRequest}
     * pulls {@code model} from the resolver so the cacheKey hash and the
     * actual generateContent call stay paired.
     */
    @Inject
    ModelResolver modelResolver;

    /**
     * CC-4a: per-task cache-enable flag. Defaults to {@code false} so rollout
     * is opt-in via SAM env var (GEMINI_CACHE_ENABLED_QUESTION_SUGGEST=true).
     * Per-task flags let us flip individual flows on/off without redeploying
     * code if a quality regression shows up in production.
     */
    @ConfigProperty(name = "gemini-cache.enabled.question-suggest", defaultValue = "false")
    boolean questionSuggestCacheEnabled;

    /**
     * CC-4b: same opt-in shape as {@link #questionSuggestCacheEnabled} but for
     * the MANAGER_GOLDEN task. Default OFF — flip via SAM env var
     * {@code GEMINI_CACHE_ENABLED_GOLDEN=true}.
     *
     * <p>Caveat: the GOLDEN cacheable portion is the system prompt only
     * ({@code GOLDEN_REVIEW_PROMPT}, ~700 tokens) — no
     * discipline/seniority/criteria — because the corpus is per-job and
     * can't be cached. That's well below the {@code MIN_CACHEABLE_TOKENS}
     * preflight threshold ({@link GeminiCacheService#MIN_CACHEABLE_TOKENS}),
     * so flipping this ON today will skip cache creation in
     * {@code GeminiCacheService.resolveOrCreate} and fall through to the
     * uncached path with no observable change. The wiring is in place so the
     * cache lights up automatically if the prompt grows past the threshold
     * later (or if we raise the per-model min by tightening the threshold).
     * Until then this flag is forward-compat scaffolding.
     */
    @ConfigProperty(name = "gemini-cache.enabled.golden", defaultValue = "false")
    boolean goldenCacheEnabled;

    /**
     * CC-4b: mirror of {@link #goldenCacheEnabled} for PEER_360_DIGEST. Default
     * OFF; SAM env var is {@code GEMINI_CACHE_ENABLED_DIGEST=true}. Same
     * caveat applies — {@code PEER_360_DIGEST_PROMPT} is ~300 tokens, well
     * below the preflight threshold, so flipping this ON today is a no-op in
     * practice.
     */
    @ConfigProperty(name = "gemini-cache.enabled.digest", defaultValue = "false")
    boolean digestCacheEnabled;

    /**
     * Bounded regeneration retry for the schema-validated worker tasks
     * (MANAGER_GOLDEN, PEER_360_DIGEST). When a generation parses but fails the
     * post-decode {@code validate()} rules that Gemini's {@code responseSchema}
     * <em>cannot</em> express — the conditional ones, chiefly "a {@code peer_360}
     * citation must carry {@code authorUserId}" and citation referential
     * integrity — we re-run the <em>whole</em> generation up to this many extra
     * times before giving up with {@code SCHEMA_INVALID}.
     *
     * <p>This is NOT a revival of the RS-2/RS-3 {@code GOLDEN_REPAIR_PROMPT}
     * loop (which fed the model's own malformed output back to it to "fix"). It
     * is a fresh re-generation: the failures we see in production are
     * non-deterministic model omissions of a non-schema-enforceable conditional
     * field, and a re-roll clears them (exactly what a manager's manual
     * "regenerate" click did for job 8fc123c3 → e48ce1f4 on 2026-06-10). On
     * retry we append the prior validation errors to the user prompt as a nudge.
     *
     * <p>Default 1 (one extra attempt). Set to 0 to restore the old
     * throw-on-first-invalid behaviour. Hand-wired unit tests that don't inject
     * this field get the int default 0 → no retry, so the RS-2/RS-3 guard tests
     * keep asserting the single-attempt contract unchanged.
     */
    @ConfigProperty(name = "gemini.schema-invalid.max-retries", defaultValue = "1")
    int schemaInvalidMaxRetries;

    @Inject ObjectMapper mapper;

    /**
     * Shared Gemini HTTP layer — extracted in RS-1. Replaces the per-service
     * {@code HttpClient} field, the duplicated {@code callGemini} method, and
     * the cross-module {@code AiReviewService.parseRetryAfterMs} call.
     */
    @Inject GeminiClient geminiClient;

    /**
     * Job repository for streaming partialText writes (GOLDEN + DIGEST).
     * Only used when the caller passes a jobId — synchronous (non-worker)
     * call sites pass null and skip the streaming path entirely.
     */
    @Inject AiJobRepository aiJobRepository;


    private static final String GOLDEN_REVIEW_PROMPT = """
            You are a professional performance review writer for a consulting company.

            You will receive a corpus containing the MANAGER'S DRAFT, the EMPLOYEE'S
            SELF REVIEW, and any completed PEER 360 REVIEWS. Each block is fenced with
            its source metadata, e.g.:

              === Manager draft (reviewId=...) ===
              === Employee self review (source=self, reviewId=...) ===
              === Peer 360 review (source=peer_360, reviewId=..., authorUserId=...) ===

            Compose a polished narrative performance review in the prescribed golden
            structure. Third person, professional tone. Weave specific quoted evidence
            from the self review and peer reviews into the manager's voice.

            ── PER-FIELD LENGTH TARGETS ──
            Set explicit word bands so the manager gets a consistent edit surface
            instead of wildly variable per-field length. Each band reflects the
            field's role and how much evidence it typically warrants. Aim for the
            midpoint; the band is a guardrail, not a target to fill.

              technologyComments         150-200 words   (2-3 paragraphs — anchor field)
              consultingComments         150-200 words   (2-3 paragraphs — anchor field)
              companyValuesComments         80-120 words   (1-2 paragraphs)
              developmentOpportunities    60-100 words   (1 focused, actionable paragraph)
              otherComments               40-80 words    (omit entirely if no notable point)

            Total goldenMirror across all comment fields: approximately 500-700 words.

            Where evidence is thin, write LESS — do not pad. Where evidence is rich,
            stay within the band — choose depth over breadth. The manager will edit
            this draft; over-long prose creates friction, under-developed prose
            forces re-writing from scratch. Aim for "this is mostly right, I'll
            tweak the wording".

            ── CITATIONS ──
            For every direct quote or attributed claim, embed an inline citation token
            of the form [c1], [c2], ... at the position where the supporting evidence
            appears, and add a matching entry in the citations map.

            Citation semantics (the schema enforces the entry shape; what it can't
            express is the SOURCE of each field):
            - source values come from the fence label ("self" or "peer_360").
            - reviewId comes from the fence.
            - questionId is the value in parentheses after "Q".
            - quote is the verbatim quoted substring of the cited answer.
            - authorUserId is REQUIRED when source is "peer_360" (schema cannot
              express conditional requires — this rule lives in validate() too).

            Citation tokens must be unique per narrative. Citations are optional — if a
            section has no direct evidence to quote you may omit citations there — but
            EVERY [cN] token in the prose MUST have a matching key in the citations map.

            Rating scale (use the same scale for technologyRating, consultingRating, overallRating):
              1 = Below Expectation
              2 = Meeting Expectation (entry — emerging/consolidating)
              3 = Meeting Expectation (mid — meets most performance criteria)
              4 = Meeting Expectation (top — fully meets performance criteria)
              5 = Exceeds Expectation (exceeds role and seniority criteria)
            """;

    // GOLDEN_REPAIR_PROMPT was removed in RS-2. The validate→repair→validate→throw
    // loop it fed is gone — Gemini's responseSchema (see ResponseSchemas.schemaForManagerGolden)
    // enforces shape at decode time, so the repair-prompt round-trip can never
    // recover anything the first call missed. SchemaInvalidException now fires
    // immediately on the residual non-schema-enforced rules (citation
    // referential integrity, peer_360-without-authorUserId).

    // QUESTION_SUGGEST_PROMPT (a String.format template, used pre-refactor)
    // was deleted in §2.5 — it had been dead code since suggestForQuestion
    // moved to buildQuestionSuggestPrompt() which builds the user prompt
    // dynamically with the full context block. The system prompt is now
    // just "You are a concise performance review writing assistant." at the
    // call site.

    public static class GoldenResult {
        public ManagerGoldenReview golden;
        public Integer tokenIn;
        public Integer tokenOut;
        public Integer repairRetries;
        /** CC-5 (AC-11.1): true iff the underlying Gemini call hit the context cache. */
        public Boolean cacheHit;
        /** CC-5 (AC-11.1): cached-token portion of tokenIn — from {@code usageMetadata.cachedContentTokenCount}. */
        public Integer cachedTokens;
    }

    public static class DigestResult {
        public Peer360Digest digest;
        public Integer tokenIn;
        public Integer tokenOut;
        public Integer repairRetries;
        /** CC-5 (AC-11.1): see {@link GoldenResult#cacheHit}. */
        public Boolean cacheHit;
        /** CC-5 (AC-11.1): see {@link GoldenResult#cachedTokens}. */
        public Integer cachedTokens;
    }

    private static final String PEER_360_DIGEST_PROMPT = """
            You are summarising peer 360 feedback about a single employee for that
            employee's manager. You will receive a collection of completed peer
            reviews. Your job is to cluster the feedback so the manager can quickly
            see what peers agree on, where they disagree, and the recurring themes
            — without having to read all reviews end-to-end.

            Rules:
            - Themes are recurring topics across multiple peers (e.g. "delivery
              leadership", "presentation confidence"). Each theme has a short label
              and a 1-2 sentence summary.
            - Consensus points are claims supported by two or more peers.
            - Dissent points are claims where peers disagree (one says X, another
              says not-X). Only include genuine disagreements, not differences of
              emphasis.
            - Sentiment semantics (schema enforces the enum; this picks the right
              value): use "constructive" when feedback is mostly developmental but
              not negative; "concerning" only when multiple peers raise serious issues.
            - Use the reviewIds provided in the input — every consensus and dissent
              point should list the reviewIds it draws from in its reviewIds array.
            """;

    // DIGEST_REPAIR_PROMPT was removed in RS-3. Mirror of the GOLDEN deletion
    // in RS-2 — Gemini's responseSchema (see ResponseSchemas.schemaForPeer360Digest)
    // enforces the required keys + sentiment enum at decode time, so the
    // repair-prompt round-trip can never recover anything the first call missed.

    public GoldenResult generateGoldenReview(String reviewText) {
        return generateGoldenReview(reviewText, null);
    }

    /**
     * Streaming-capable overload. When {@code jobId != null}, routes Gemini
     * through {@link GeminiClient#callStreaming} and persists cumulative
     * partialText snapshots to AiJobs as Gemini emits SSE chunks — the manager
     * watches the AI Review tab fill in live (mirror of INSIGHTS streaming).
     * Pass {@code null} from synchronous callers (no worker job to attach to).
     */
    public GoldenResult generateGoldenReview(String reviewText, String jobId) {
        if (mockMode) {
            GoldenResult mock = new GoldenResult();
            mock.golden = new ManagerGoldenReview();
            mock.golden.technologyRating = 4;
            mock.golden.technologyComments = "Mock: strong technical skills demonstrated across the cycle.";
            mock.golden.consultingRating = 4;
            mock.golden.consultingComments = "Mock: effective stakeholder management on multiple engagements.";
            mock.golden.companyValuesComments = "Mock: demonstrates all company values consistently.";
            mock.golden.developmentOpportunities = "Mock: focus on executive communication.";
            mock.golden.otherComments = "Mock: solid overall performance.";
            mock.golden.overallRating = 4;
            return mock;
        }

        String userPromptBase = "Here are the manager's review answers to convert:\n\n" + reviewText;
        // RS-2: Gemini server-side responseSchema enforces shape (8 required
        // content keys + integer-1-to-5 ratings + citation entry shape) at
        // decode time. What it CANNOT enforce — conditional rules (a peer_360
        // citation must carry authorUserId) and citation referential integrity —
        // is caught by ManagerGoldenReview.validate() below. Those residual
        // failures are non-deterministic model omissions, so a clean
        // re-generation usually clears them: attemptWithSchemaRetry re-rolls the
        // whole call up to schemaInvalidMaxRetries times (default 1) before
        // surfacing SCHEMA_INVALID (see field javadoc; incident 2026-06-10).
        int[] budgets = budgetsFor("manager-golden");
        String goldenModel = modelResolver.modelFor(TaskMetadata.TASK_MANAGER_GOLDEN);

        Attempt<ManagerGoldenReview> attempt = attemptWithSchemaRetry("MANAGER_GOLDEN", correctiveHint -> {
            // On a retry, append the prior validation errors as a corrective
            // nudge. GOLDEN caches the system prompt only (corpus is per-job),
            // so the dynamic userPrompt is the right place for the hint and the
            // cache stays valid.
            String userPrompt = (correctiveHint == null) ? userPromptBase : userPromptBase + correctiveHint;
            // CC-4b: opt-in caching of the system prompt. GOLDEN has no static
            // user-role preamble (no discipline/seniority block; the corpus is
            // per-job), so when caching is on we cache the systemInstruction
            // only and the userPrompt is the dynamic per-job text. uncachedPrompt
            // equals userPrompt because there's no static user-role content to
            // reconstruct on cache miss — the same bytes go in either mode.
            GeminiRequest request;
            if (goldenCacheEnabled) {
                CacheRequest cacheReq = new CacheRequest(
                        TaskMetadata.TASK_MANAGER_GOLDEN,
                        TaskMetadata.PROMPT_VERSION_MANAGER_GOLDEN,
                        null, null,                       // no discipline/seniority — universal prompt
                        GOLDEN_REVIEW_PROMPT,
                        "",                                // no extraPrefix — system instruction is all we cache
                        goldenModel,
                        600L);                             // §D5: 10 min TTL — one-shot per review
                request = GeminiRequest.of("manager-golden", GOLDEN_REVIEW_PROMPT,
                                userPrompt, budgets[0], budgets[1], goldenModel)
                        .withResponseSchema(ResponseSchemas.schemaForManagerGolden())
                        .withCacheRequest(cacheReq, userPrompt);
            } else {
                request = GeminiRequest.of("manager-golden", GOLDEN_REVIEW_PROMPT,
                                userPrompt, budgets[0], budgets[1], goldenModel)
                        .withResponseSchema(ResponseSchemas.schemaForManagerGolden());
            }
            // Streaming path: route Gemini via SSE so partialText snapshots land
            // on AiJobs every ~500ms / 200 chars during PROCESSING. The
            // manager-side "AI Review" tab polls those snapshots and renders
            // them through the rAF-smoothed typewriter buffer. Non-streaming
            // path preserved for any synchronous caller passing jobId=null. On a
            // retry the snapshots simply overwrite — the manager sees the valid
            // re-generation.
            final GeminiResult call;
            if (jobId != null) {
                final String streamJobId = jobId;
                call = geminiClient.callStreaming(request, snapshot ->
                        aiJobRepository.updatePartialText(streamJobId, snapshot));
            } else {
                call = geminiClient.call(request);
            }

            ManagerGoldenReview parsed = tryParse(call.text());
            // 2026-05-25 hotfix follow-up: scrub dangling [cN] tags before
            // validate() runs. Even with schema-required citations, Gemini
            // sometimes emits citations: {} while still embedding [cN] in prose
            // — without this scrub the validate() referential-integrity check
            // would surface every short-input case as SCHEMA_INVALID. The WARN
            // is a canary: if it fires in production, Gemini regressed on the
            // schema-enforced contract.
            if (parsed != null) {
                Map<String, ?> citations = parsed.citations;
                Set<String> dangling = findDanglingCitationsGolden(parsed, citations);
                if (!dangling.isEmpty()) {
                    LOG.warnf("MANAGER_GOLDEN dangling citations scrubbed — Gemini regression canary. "
                            + "task=manager-golden originalTechCommentsLen=%d originalConsultingCommentsLen=%d "
                            + "originalCatalystFlowLen=%d originalDevOppLen=%d originalOtherLen=%d dangling=%s",
                            len(parsed.technologyComments), len(parsed.consultingComments),
                            len(parsed.companyValuesComments), len(parsed.developmentOpportunities),
                            len(parsed.otherComments), dangling);
                    parsed.technologyComments = scrubDanglingCitations(parsed.technologyComments, citations);
                    parsed.consultingComments = scrubDanglingCitations(parsed.consultingComments, citations);
                    parsed.companyValuesComments = scrubDanglingCitations(parsed.companyValuesComments, citations);
                    parsed.developmentOpportunities = scrubDanglingCitations(parsed.developmentOpportunities, citations);
                    parsed.otherComments = scrubDanglingCitations(parsed.otherComments, citations);
                }
            }
            List<String> errors = (parsed != null) ? parsed.validate() : List.of("response was not valid JSON");
            return new Attempt<>(call, parsed, errors);
        });

        GoldenResult result = new GoldenResult();
        result.tokenIn = attempt.call.tokenIn();
        result.tokenOut = attempt.call.tokenOut();
        // Number of regenerations the success required (0 on first-try success).
        // A persisted value > 0 means the model needed a re-roll to satisfy the
        // non-schema-enforceable rules — useful as a quality signal, no longer
        // "alarm-worthy" (that interpretation predated the bounded retry).
        result.repairRetries = attempt.retriesUsed;
        // CC-5 (AC-11.1): plumb cache attribution to the AiJobs row.
        result.cacheHit = attempt.call.cacheHit();
        result.cachedTokens = attempt.call.cachedTokens();
        result.golden = attempt.parsed;
        return result;
    }

    // ── Bounded regeneration retry (shared: MANAGER_GOLDEN, PEER_360_DIGEST) ──

    /**
     * One generation attempt: the raw Gemini call, the parsed payload (null when
     * the response wasn't valid JSON), and the {@code validate()} errors (empty
     * == schema-valid). {@code retriesUsed} is stamped by
     * {@link #attemptWithSchemaRetry} on the attempt it returns.
     */
    private static final class Attempt<T> {
        final GeminiResult call;
        final T parsed;
        final List<String> errors;
        int retriesUsed;
        Attempt(GeminiResult call, T parsed, List<String> errors) {
            this.call = call;
            this.parsed = parsed;
            this.errors = errors;
        }
    }

    @FunctionalInterface
    private interface SchemaAttempt<T> {
        /**
         * Run one generation. {@code correctiveHint} is {@code null} on the first
         * attempt and the appended error-nudge string on retries.
         */
        Attempt<T> run(String correctiveHint);
    }

    /**
     * Run a schema-validated generation with bounded regeneration retry. Returns
     * the first attempt whose {@code validate()} passed. If every attempt
     * (1 + {@link #schemaInvalidMaxRetries}) failed, throws
     * {@link SchemaInvalidException} carrying the LAST attempt's telemetry +
     * errors, with {@code repairRetries} = the number of retries actually spent.
     *
     * <p>Unlike the deleted RS-2/RS-3 repair-prompt loop, each retry is a clean
     * re-generation (optionally nudged by the prior errors), not a "fix your own
     * JSON" round-trip — the right tool for the non-deterministic conditional
     * failures that survive responseSchema. See {@link #schemaInvalidMaxRetries}.
     */
    private <T> Attempt<T> attemptWithSchemaRetry(String taskLabel, SchemaAttempt<T> fn) {
        int maxRetries = Math.max(0, schemaInvalidMaxRetries);
        Attempt<T> last = null;
        for (int retry = 0; retry <= maxRetries; retry++) {
            String hint = (retry == 0) ? null : buildCorrectiveHint(last.errors);
            last = fn.run(hint);
            if (last.errors.isEmpty()) {
                last.retriesUsed = retry;
                return last;
            }
            if (retry < maxRetries) {
                LOG.warnf("%s schema invalid (attempt %d/%d) — regenerating: %s",
                        taskLabel, retry + 1, maxRetries + 1, last.errors);
            }
        }
        // Exhausted — surface SCHEMA_INVALID with the last attempt's telemetry so
        // the FAILED AiJobs row carries cost attribution + the retry count.
        LOG.errorf("%s schema invalid after %d attempt(s) (responseSchema-enforced, retries exhausted): %s",
                taskLabel, maxRetries + 1, last.errors);
        throw new SchemaInvalidException(last.errors, last.call.text(),
                last.call.tokenIn(), last.call.tokenOut(), maxRetries, last.call.modelUsed(),
                last.call.cacheHit(), last.call.cachedTokens());
    }

    /**
     * The retry nudge appended to the user prompt — echoes the failed rules so
     * the model can self-correct on re-generation. Task-agnostic; the two
     * call-outs (peer_360 authorUserId, citation referential integrity) are the
     * dominant residual failures and are harmless when irrelevant.
     */
    private static String buildCorrectiveHint(List<String> errors) {
        return "\n\nIMPORTANT — your previous response was rejected for these schema violations:\n- "
                + String.join("\n- ", errors)
                + "\n\nProduce the COMPLETE JSON again, fixing every issue above. In particular: any citation "
                + "whose source is \"peer_360\" MUST include a non-empty authorUserId, and every [cN] token in "
                + "the prose MUST have a matching entry in the citations map.";
    }

    /** Parse Gemini text → ManagerGoldenReview. Returns null on JSON parse failure. */
    private ManagerGoldenReview tryParse(String text) {
        try {
            return mapper.readValue(stripMarkdown(text), ManagerGoldenReview.class);
        } catch (Exception e) {
            LOG.debugf(e, "Golden response was not parseable JSON");
            return null;
        }
    }

    // ── Citation scrub (2026-05-25 hotfix follow-up) ────────────────────────
    // Intentionally duplicated from AiReviewService rather than promoted to a
    // shared utility class — keeps the blast radius of this fix small. If a
    // third call site needs it, extract then.

    private static final Pattern CITATION_MARKER_GOLDEN = Pattern.compile("\\[(c\\d+)\\]");

    private static Set<String> findDanglingCitationsGolden(ManagerGoldenReview g, Map<String, ?> citations) {
        Set<String> referenced = new HashSet<>();
        collectKeys(g.technologyComments, referenced);
        collectKeys(g.consultingComments, referenced);
        collectKeys(g.companyValuesComments, referenced);
        collectKeys(g.developmentOpportunities, referenced);
        collectKeys(g.otherComments, referenced);
        if (referenced.isEmpty()) return Set.of();
        if (citations != null && !citations.isEmpty()) {
            referenced.removeAll(citations.keySet());
        }
        return referenced;
    }

    private static void collectKeys(String text, Set<String> sink) {
        if (text == null || text.isEmpty()) return;
        Matcher m = CITATION_MARKER_GOLDEN.matcher(text);
        while (m.find()) sink.add(m.group(1));
    }

    private static int len(String s) { return s == null ? 0 : s.length(); }

    /**
     * Strip any {@code [cN]} tag whose key is missing from {@code citations}
     * (or strip all tags when citations is null/empty). Cleans up the
     * immediately-following space and collapses double-space → single space.
     * Duplicate of {@code AiReviewService.scrubDanglingCitations} — see the
     * comment above this section for the rationale.
     */
    private static String scrubDanglingCitations(String text, Map<String, ?> citations) {
        if (text == null || text.isEmpty()) return text;
        Matcher m = CITATION_MARKER_GOLDEN.matcher(text);
        if (!m.find()) return text;
        m.reset();
        StringBuilder out = new StringBuilder(text.length());
        boolean scrubbed = false;
        int last = 0;
        while (m.find()) {
            String key = m.group(1);
            boolean present = citations != null && !citations.isEmpty() && citations.containsKey(key);
            if (present) {
                out.append(text, last, m.end());
                last = m.end();
            } else {
                int stripStart = m.start();
                int stripEnd = m.end();
                if (stripEnd < text.length() && text.charAt(stripEnd) == ' ') {
                    stripEnd++;
                } else if (stripStart > 0 && text.charAt(stripStart - 1) == ' ') {
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
        String result = out.toString();
        while (result.contains("  ")) {
            result = result.replace("  ", " ");
        }
        return result;
    }

    /**
     * Cluster N raw peer 360 reviews into themes / consensus / dissent for the
     * manager. ADR-2.
     *
     * <p>RS-3: Gemini server-side {@code responseSchema} enforces the four
     * top-level keys plus the {@code overallSentiment} enum at decode time,
     * eliminating the validate→repair→validate→throw loop. The
     * {@code Peer360Digest.validate()} call below stays as the defensive last
     * line; any error reaching it is a real {@code SCHEMA_INVALID} signal.
     *
     * @param peerCorpus formatted text from {@link #buildPeer360Corpus} —
     *                   already includes per-review headers and reviewIds.
     */
    public DigestResult generatePeer360Digest(String peerCorpus) {
        return generatePeer360Digest(peerCorpus, null);
    }

    /**
     * Streaming-capable overload. Mirror of {@link #generateGoldenReview(String, String)}:
     * when {@code jobId != null} the Gemini call runs through SSE and partialText
     * snapshots flow to AiJobs as themes/consensus/dissent prose emerges. The
     * manager-side 360 Digest tab polls those snapshots and renders via the
     * shared rAF-smoothed typewriter buffer.
     */
    public DigestResult generatePeer360Digest(String peerCorpus, String jobId) {
        if (mockMode) {
            DigestResult mock = new DigestResult();
            mock.digest = new Peer360Digest();
            Peer360Digest.Theme t = new Peer360Digest.Theme();
            t.theme = "Delivery leadership";
            t.summary = "Mock: peers consistently call out strong delivery ownership.";
            mock.digest.themes = List.of(t);
            Peer360Digest.Point c = new Peer360Digest.Point();
            c.point = "Mock: takes ownership of stakeholder relationships.";
            c.reviewIds = List.of("mock-r-1", "mock-r-2");
            mock.digest.consensus = List.of(c);
            mock.digest.dissent = List.of();
            mock.digest.overallSentiment = "positive";
            return mock;
        }

        String userPromptBase = "Here are the completed peer 360 reviews to summarise:\n\n" + peerCorpus;
        int[] budgets = budgetsFor("peer-360-digest");
        String digestModel = modelResolver.modelFor(TaskMetadata.TASK_PEER_360_DIGEST);

        Attempt<Peer360Digest> attempt = attemptWithSchemaRetry("PEER_360_DIGEST", correctiveHint -> {
            String userPrompt = (correctiveHint == null) ? userPromptBase : userPromptBase + correctiveHint;
            // CC-4b: mirror of generateGoldenReview — cache the system prompt
            // only when the per-task flag is on. DIGEST has no static user-role
            // preamble either (peerCorpus is wholly per-job), so
            // uncachedPrompt = userPrompt.
            GeminiRequest request;
            if (digestCacheEnabled) {
                CacheRequest cacheReq = new CacheRequest(
                        TaskMetadata.TASK_PEER_360_DIGEST,
                        TaskMetadata.PROMPT_VERSION_PEER_360_DIGEST,
                        null, null,
                        PEER_360_DIGEST_PROMPT,
                        "",
                        digestModel,
                        600L);                             // §D5: 10 min TTL — one-shot per (cycle, subject)
                request = GeminiRequest.of("peer-360-digest", PEER_360_DIGEST_PROMPT,
                                userPrompt, budgets[0], budgets[1], digestModel)
                        .withResponseSchema(ResponseSchemas.schemaForPeer360Digest())
                        .withCacheRequest(cacheReq, userPrompt);
            } else {
                request = GeminiRequest.of("peer-360-digest", PEER_360_DIGEST_PROMPT,
                                userPrompt, budgets[0], budgets[1], digestModel)
                        .withResponseSchema(ResponseSchemas.schemaForPeer360Digest());
            }
            // Streaming path (worker only; sync caller passes jobId=null). See
            // generateGoldenReview above for the same pattern + rationale.
            final GeminiResult call;
            if (jobId != null) {
                final String streamJobId = jobId;
                call = geminiClient.callStreaming(request, snapshot ->
                        aiJobRepository.updatePartialText(streamJobId, snapshot));
            } else {
                call = geminiClient.call(request);
            }
            Peer360Digest parsed = tryParseDigest(call.text());
            List<String> errors = (parsed != null) ? parsed.validate() : List.of("response was not valid JSON");
            return new Attempt<>(call, parsed, errors);
        });

        DigestResult result = new DigestResult();
        result.tokenIn = attempt.call.tokenIn();
        result.tokenOut = attempt.call.tokenOut();
        result.repairRetries = attempt.retriesUsed;
        // CC-5 (AC-11.1): plumb cache attribution to the AiJobs row.
        result.cacheHit = attempt.call.cacheHit();
        result.cachedTokens = attempt.call.cachedTokens();
        result.digest = attempt.parsed;
        return result;
    }

    private Peer360Digest tryParseDigest(String text) {
        try {
            return mapper.readValue(stripMarkdown(text), Peer360Digest.class);
        } catch (Exception e) {
            LOG.debugf(e, "Digest response was not parseable JSON");
            return null;
        }
    }

    /**
     * Format a list of peer reviews (each = list of (questionText, response) pairs
     * + the row's reviewId) into a Gemini-ready corpus. Each review block is
     * fenced with its reviewId so the model can cite back to specific rows.
     */
    public String buildPeer360Corpus(List<Peer360ReviewBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (var b : blocks) {
            sb.append("=== Peer review ").append(b.reviewId).append(" ===\n");
            for (var qa : b.answers) {
                if (qa.response != null && !qa.response.isBlank()) {
                    sb.append("Q: ").append(qa.questionText).append("\n");
                    sb.append("A: ").append(qa.response).append("\n\n");
                }
            }
        }
        return sb.toString().trim();
    }

    public static class Peer360ReviewBlock {
        public String reviewId;
        public List<AnswerWithText> answers;
    }

    private static Integer sumTokens(Integer a, Integer b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + b;
    }

    @RegisterForReflection
    public static class SuggestionResult {
        public String suggestion;
        /**
         * RS-4 follow-up: changed from primitive {@code boolean} to {@link Boolean}
         * so Jackson surfaces a missing JSON field as {@code null} instead of
         * silently defaulting to {@code false}. {@link #validateSuggestion}
         * now rejects {@code null}, catching the case where Gemini emits
         * {@code {"suggestion":"ok","citations":{}}} despite responseSchema
         * marking the field required.
         */
        public Boolean isGoodAnswer;
        public java.util.Map<String, SuggestionCitation> citations;
        // Telemetry fields — captured by suggestForQuestion, persisted as
        // separate DDB columns by runJobInProcess. @JsonIgnore keeps them out of
        // resultJson so the frontend payload stays unchanged.
        @com.fasterxml.jackson.annotation.JsonIgnore public Integer tokenIn;
        @com.fasterxml.jackson.annotation.JsonIgnore public Integer tokenOut;
        @com.fasterxml.jackson.annotation.JsonIgnore public Integer repairRetries;
        /** CC-5 (AC-11.1): cache attribution — JsonIgnored, persisted separately on AiJobs. */
        @com.fasterxml.jackson.annotation.JsonIgnore public Boolean cacheHit;
        @com.fasterxml.jackson.annotation.JsonIgnore public Integer cachedTokens;
    }

    @RegisterForReflection
    public static class SuggestionCitation {
        public String source;
        public String reviewId;
        public String questionId;
        public String quote;
        public String section;
        public String authorUserId;
    }

    public SuggestionResult suggestForQuestion(QuestionSuggestRequest req,
                                               List<String> techCriteria,
                                               List<String> consultingCriteria,
                                               String managerStyleJson) {
        if (mockMode) {
            SuggestionResult mock = new SuggestionResult();
            mock.isGoodAnswer = true;
            mock.suggestion = "Strong answer with concrete detail and clear stakeholder impact.";
            mock.citations = java.util.Map.of();
            return mock;
        }

        // RS-4: Gemini server-side responseSchema enforces top-level shape
        // (isGoodAnswer/suggestion/citations) + the citation source enum
        // (self/peer_360/peer_digest) at decode time. The validate→repair→
        // validate→throw loop is gone. The previous broad catch(Exception)
        // that returned a stub "AI suggestion temporarily unavailable"
        // SuggestionResult — masking schema/telemetry/rate-limit failures
        // and lying to the manager — is gone too. Exceptions propagate to runJobInProcess
        // which writes the AiJobs row as FAILED with accurate telemetry.
        //
        // CC-4a: when questionSuggestCacheEnabled is true, split the prompt
        // into a static cacheable prefix (criteria + style + task) and a
        // per-call dynamic user prompt (question + manager answer + employee
        // review). The cacheable prefix is keyed by (discipline, seniority,
        // promptVersion) so a manager drafting many questions for the same
        // (discipline, seniority) reuses one Gemini cache. When the flag is
        // off (default), build the combined prompt as today — no shape change.
        String systemPrompt = "You are a concise performance review writing assistant.";
        int[] budgets = budgetsFor("manager-question-suggest");
        GeminiRequest request;
        if (questionSuggestCacheEnabled) {
            String cacheablePrefix = buildQuestionSuggestCacheablePrefix(req.discipline,
                    req.seniority, techCriteria, consultingCriteria, managerStyleJson);
            String dynamicUserPrompt = buildQuestionSuggestDynamicPart(req);
            // Cache-miss fallback prompt: the full cache-OFF combined form,
            // byte-identical to what we'd send if questionSuggestCacheEnabled
            // were false. GeminiClient sends this verbatim when the cache layer
            // returns null — caller owns byte order, no reconstruction inside
            // the client (CC-4a MEDIUM #3 follow-up).
            String uncachedPrompt = buildQuestionSuggestPrompt(req, techCriteria,
                    consultingCriteria, managerStyleJson);
            String questionSuggestModel = modelResolver.modelFor(TaskMetadata.TASK_MANAGER_QUESTION_SUGGEST);
            CacheRequest cacheReq = new CacheRequest(
                    TaskMetadata.TASK_MANAGER_QUESTION_SUGGEST,
                    TaskMetadata.PROMPT_VERSION_QUESTION_SUGGEST,
                    req.discipline, req.seniority,
                    systemPrompt,
                    cacheablePrefix,
                    questionSuggestModel,
                    3600L); // §D5: 1h TTL — highest-reuse task in a manager drafting session
            request = GeminiRequest.of("manager-question-suggest",
                            systemPrompt, dynamicUserPrompt, budgets[0], budgets[1], questionSuggestModel)
                    .withResponseSchema(ResponseSchemas.schemaForQuestionSuggest())
                    .withCacheRequest(cacheReq, uncachedPrompt);
        } else {
            String prompt = buildQuestionSuggestPrompt(req, techCriteria,
                    consultingCriteria, managerStyleJson);
            String questionSuggestModel = modelResolver.modelFor(TaskMetadata.TASK_MANAGER_QUESTION_SUGGEST);
            request = GeminiRequest.of("manager-question-suggest",
                            systemPrompt, prompt, budgets[0], budgets[1], questionSuggestModel)
                    .withResponseSchema(ResponseSchemas.schemaForQuestionSuggest());
        }
        GeminiResult call = geminiClient.call(request);

        SuggestionResult parsed = tryParseSuggestion(call.text());
        normalizeSuggestionCitations(parsed, req);
        List<String> errors = validateSuggestion(parsed, req);
        if (!errors.isEmpty()) {
            // Defensive — near-zero rate post-RS-4. Anything reaching here is
            // a conditional rule responseSchema can't express (self-needs-
            // questionId, peer_360-needs-reviewId, allowed-self-id membership)
            // OR a genuine Gemini decoder failure. Telemetry on the exception
            // so runJobInProcess persists cost attribution + repair-retry=0
            // alarm signal onto the FAILED AiJobs row.
            LOG.errorf("Question suggestion schema invalid (responseSchema-enforced, no repair retry): %s", errors);
            throw new SchemaInvalidException(errors, call.text(),
                    call.tokenIn(), call.tokenOut(), 0, call.modelUsed(),
                    call.cacheHit(), call.cachedTokens());
        }
        if (parsed != null) {
            parsed.tokenIn = call.tokenIn();
            parsed.tokenOut = call.tokenOut();
            parsed.repairRetries = 0;
            // CC-5 (AC-11.1): plumb cache attribution. QUESTION_SUGGEST is the
            // first task to actually exercise caching in production once the
            // flag is on — the hit-rate dashboard reads from this column.
            parsed.cacheHit = call.cacheHit();
            parsed.cachedTokens = call.cachedTokens();
        }
        return parsed;
    }

    private SuggestionResult tryParseSuggestion(String text) {
        try {
            return mapper.readValue(stripMarkdown(text), SuggestionResult.class);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> validateSuggestion(SuggestionResult result) {
        return validateSuggestion(result, null);
    }

    private List<String> validateSuggestion(SuggestionResult result, QuestionSuggestRequest req) {
        java.util.List<String> errors = new java.util.ArrayList<>();
        if (result == null) {
            errors.add("response was not valid JSON");
            return errors;
        }
        // RS-4 follow-up: isGoodAnswer was primitive boolean pre-fix, so a
        // missing JSON field silently defaulted to false and validation passed.
        // Now it's Boolean — null means Gemini omitted the field despite the
        // responseSchema declaring it required. Surface as schema-invalid.
        if (result.isGoodAnswer == null) errors.add("isGoodAnswer is required");
        if (result.suggestion == null || result.suggestion.isBlank()) errors.add("suggestion is required");
        if (result.citations == null) errors.add("citations is required (use {})");
        String suggestion = result.suggestion == null ? "" : result.suggestion;
        java.util.regex.Pattern bracket = java.util.regex.Pattern.compile("\\[([^\\]]+)\\]");
        java.util.regex.Matcher bm = bracket.matcher(suggestion);
        while (bm.find()) {
            String inside = bm.group(1) == null ? "" : bm.group(1).trim();
            if (!inside.matches("c\\d+")) {
                errors.add("invalid citation token format [" + inside + "]; use [cN] only");
            }
        }
        java.util.regex.Pattern token = java.util.regex.Pattern.compile("\\[(c\\d+)\\]");
        java.util.regex.Matcher m = token.matcher(suggestion);
        while (m.find()) {
            String k = m.group(1);
            if (result.citations == null || !result.citations.containsKey(k) || result.citations.get(k) == null) {
                errors.add("citation [" + k + "] missing from citations map");
            }
        }
        java.util.Set<String> allowedSelfQuestionIds = allowedSelfQuestionIds(req);
        if (result.citations != null) {
            for (var e : result.citations.entrySet()) {
                String key = e.getKey();
                SuggestionCitation c = e.getValue();
                if (c == null) {
                    errors.add("citation [" + key + "] is null");
                    continue;
                }
                if (c.source == null || c.source.isBlank()) {
                    errors.add("citation [" + key + "] source is required");
                    continue;
                }
                if (c.quote == null || c.quote.isBlank()) {
                    errors.add("citation [" + key + "] quote is required");
                }
                String src = c.source.trim();
                if (!"self".equals(src) && !"peer_360".equals(src) && !"peer_digest".equals(src)) {
                    errors.add("citation [" + key + "] source must be self|peer_360|peer_digest");
                }
                if ("self".equals(src) && (c.questionId == null || c.questionId.isBlank())) {
                    errors.add("citation [" + key + "] self source requires questionId");
                }
                if ("self".equals(src)
                        && c.questionId != null
                        && !c.questionId.isBlank()
                        && !allowedSelfQuestionIds.isEmpty()
                        && !allowedSelfQuestionIds.contains(c.questionId.trim())) {
                    errors.add("citation [" + key + "] self questionId is not one of allowed employee answer IDs");
                }
                if ("peer_360".equals(src)) {
                    if (c.reviewId == null || c.reviewId.isBlank()) {
                        errors.add("citation [" + key + "] peer_360 source requires reviewId");
                    }
                    if (c.questionId == null || c.questionId.isBlank()) {
                        errors.add("citation [" + key + "] peer_360 source requires questionId");
                    }
                }
            }
        }
        return errors;
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase()
            .replaceFirst("^q\\s*:\\s*", "")
            .replaceAll("[^a-z0-9]+", " ")
            .trim();
    }

    private void normalizeSuggestionCitations(SuggestionResult result, QuestionSuggestRequest req) {
        if (result == null || result.citations == null || req == null || req.employeeReview == null || req.employeeReview.answers == null) {
            return;
        }

        java.util.Map<String, String> byId = new java.util.HashMap<>();
        java.util.Map<String, String> byText = new java.util.HashMap<>();
        for (QuestionSuggestRequest.EmployeeAnswer a : req.employeeReview.answers) {
            if (a == null) continue;
            if (a.questionId != null && !a.questionId.isBlank()) {
                byId.put(a.questionId.trim(), a.questionId.trim());
            }
            String t = norm(a.questionText);
            if (!t.isBlank() && a.questionId != null && !a.questionId.isBlank()) {
                byText.putIfAbsent(t, a.questionId.trim());
            }
        }

        java.util.Iterator<java.util.Map.Entry<String, SuggestionCitation>> it = result.citations.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            SuggestionCitation c = e.getValue();
            if (c == null || c.source == null) continue;
            if (!"self".equals(c.source.trim())) continue;

            String raw = c.questionId == null ? "" : c.questionId.trim();
            String mapped = byId.get(raw);
            if (mapped == null) mapped = byText.get(norm(raw));
            if (mapped == null && c.section != null && !c.section.isBlank()) mapped = byText.get(norm(c.section));

            if (mapped != null && !mapped.isBlank()) {
                c.questionId = mapped;
            }
        }
    }

    private java.util.Set<String> allowedSelfQuestionIds(QuestionSuggestRequest req) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        if (req == null || req.employeeReview == null || req.employeeReview.answers == null) {
            return ids;
        }
        for (QuestionSuggestRequest.EmployeeAnswer a : req.employeeReview.answers) {
            if (a != null && a.questionId != null && !a.questionId.isBlank()) {
                ids.add(a.questionId.trim());
            }
        }
        return ids;
    }

    private String allowedSelfCitationQuestionIdsBlock(QuestionSuggestRequest req) {
        if (req == null || req.employeeReview == null || req.employeeReview.answers == null) {
            return "ALLOWED SELF CITATION QUESTION IDS\nNone provided.\n\n";
        }
        StringBuilder sb = new StringBuilder("ALLOWED SELF CITATION QUESTION IDS\n");
        for (QuestionSuggestRequest.EmployeeAnswer a : req.employeeReview.answers) {
            if (a == null || a.questionId == null || a.questionId.isBlank()) continue;
            sb.append("- ").append(a.questionId.trim()).append(": ")
              .append(a.questionText == null ? "" : a.questionText.trim())
              .append("\n");
        }
        return sb.append("\n").toString();
    }

    // buildQuestionSuggestRepairPrompt removed in RS-4. The repair retry it
    // fed is gone — Gemini's responseSchema (see
    // ResponseSchemas.schemaForQuestionSuggest) enforces top-level shape +
    // source enum at decode time. validateSuggestion's conditional rules
    // (self-needs-questionId, peer_360-needs-reviewId, allowed-id membership)
    // now surface as SchemaInvalidException on the first call.

    /**
     * Build the static-per-(discipline, seniority) portion of the QUESTION_SUGGEST
     * prompt: ROLE EXPECTATIONS + MANAGER STYLE REFERENCE + TASK instructions
     * + citation rules. Used as {@link CacheRequest#extraPrefix} when
     * {@link #questionSuggestCacheEnabled} is true.
     *
     * <p>This portion is stable across every QUESTION_SUGGEST call within the
     * same (discipline, seniority) drafting session — which is the high-reuse
     * pattern CC-4a targets. The dynamic portion (question context, manager's
     * current answer, employee self review, allowed IDs, peer digest) lives in
     * {@link #buildQuestionSuggestDynamicPart}.
     *
     * <p>When the cache flag is OFF, both halves are concatenated by
     * {@link #buildQuestionSuggestPrompt} into a single user prompt — the
     * pre-CC-4a behaviour with no shape change.
     *
     * <h2>No directional words in the citation rules</h2>
     * The source=self citation rule references the ALLOWED block neutrally
     * ("in this request") and MUST NOT use "above" / "below". The block's
     * relative position differs by transport:
     * <ul>
     *   <li>Cache-ON: this prefix is sent first as {@code cachedContent}; the
     *       ALLOWED block (part of the dynamic half) is appended AFTER it.</li>
     *   <li>Cache-OFF: {@link #buildQuestionSuggestPrompt} emits
     *       dynamic + prefix, so the ALLOWED block sits BEFORE the rule.</li>
     * </ul>
     * One static prompt has to work in both orders. A previous revision
     * included a "Do NOT use directional words..." instruction in the prompt
     * itself — Gemini doesn't need that meta-instruction, and including
     * "above"/"below" in the instruction broke the regression test that bans
     * those words. {@code buildQuestionSuggestCacheablePrefix_*} in
     * {@code ManagerGuidanceServiceQuestionSuggestSchemaTest} enforces this
     * across the whole prefix.
     */
    String buildQuestionSuggestCacheablePrefix(String discipline,
                                               String seniority,
                                               List<String> techCriteria,
                                               List<String> consultingCriteria,
                                               String managerStyleJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("ROLE EXPECTATIONS (EXACT LEVEL)\n")
          .append("Discipline: ").append(discipline).append("\n")
          .append("Seniority: ").append(seniority).append("\n")
          .append("Technology criteria: ").append(techCriteria).append("\n")
          .append("Consulting criteria: ").append(consultingCriteria).append("\n\n");

        if (managerStyleJson != null && !managerStyleJson.isBlank()) {
            sb.append("MANAGER STYLE REFERENCE (V2)\n").append(managerStyleJson).append("\n\n");
        }

        sb.append("""
                TASK
                Evaluate the manager's current answer for THIS question only.
                Use the employee full review to pull relevant evidence. If peer digest is provided, you may use it; otherwise do not reference peer evidence.
                Apply a lenient threshold:
                - Default to isGoodAnswer=true when the answer is relevant and has meaningful specifics.
                - Set isGoodAnswer=false only when the answer is clearly vague, off-topic, or too thin.
                - Do not suggest minor wording polish for already-strong answers.

                Citation semantics (the schema enforces the entry shape; what
                it can't express is referential + conditional + format rules):
                - Citation rule: every [cN] token in suggestion must have a
                  matching key in citations.
                - Citation token format is STRICT: use only single tokens like
                  [c1], [c2]. Never group tokens in one bracket (invalid:
                  [c1, c2]).
                - If no citations are used, set citations to {} and include no
                  [cN] token.
                - For source=self citations, questionId must be one of the
                  provided employeeReview.answers.questionId values (see
                  the ALLOWED SELF CITATION QUESTION IDS block in this
                  request).
                - For source=peer_360, reviewId is required.
                """);
        return sb.toString();
    }

    /**
     * Build the per-call (dynamic) portion of the QUESTION_SUGGEST prompt.
     * When the cache flag is on, this is sent as the userPrompt with the
     * cacheable prefix prepended via Gemini's cachedContents API. When the
     * flag is off, this is concatenated with the cacheable prefix into a
     * single user prompt by {@link #buildQuestionSuggestPrompt}.
     */
    String buildQuestionSuggestDynamicPart(QuestionSuggestRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append("QUESTION CONTEXT\n")
          .append("questionId: ").append(req.questionId).append("\n")
          .append("questionText: ").append(req.questionText).append("\n\n");

        sb.append("MANAGER CURRENT ANSWER\n").append(req.managerCurrentAnswer).append("\n\n");

        sb.append("EMPLOYEE SELF REVIEW (FULL)\n");
        for (QuestionSuggestRequest.EmployeeAnswer a : req.employeeReview.answers) {
            if (a == null || a.response == null || a.response.isBlank()) continue;
            sb.append("Q: ").append(a.questionText != null ? a.questionText : a.questionId).append("\n")
              .append("A: ").append(a.response).append("\n\n");
        }
        sb.append(allowedSelfCitationQuestionIdsBlock(req));

        if (req.peerDigest != null) {
            sb.append("PEER DIGEST (OPTIONAL)\n")
              .append("overallSentiment: ").append(req.peerDigest.overallSentiment == null ? "" : req.peerDigest.overallSentiment).append("\n")
              .append("themes: ").append(req.peerDigest.themes == null ? "[]" : req.peerDigest.themes).append("\n")
              .append("consensus: ").append(req.peerDigest.consensus == null ? "[]" : req.peerDigest.consensus).append("\n")
              .append("dissent: ").append(req.peerDigest.dissent == null ? "[]" : req.peerDigest.dissent).append("\n\n");
        } else {
            sb.append("PEER DIGEST (OPTIONAL)\nNot provided. Do not infer peer evidence.\n\n");
        }
        return sb.toString();
    }

    /**
     * Build the combined QUESTION_SUGGEST prompt (dynamic part + cacheable
     * prefix). Used by the cache-OFF path in {@link #suggestForQuestion} to
     * preserve the pre-CC-4a byte order EXACTLY: dynamic-first, then static
     * (ROLE / STYLE / TASK / citation rules). This matches what
     * {@code buildQuestionSuggestPrompt} produced before CC-4a — production
     * default is cache-OFF, so this MUST be byte-identical to pre-CC-4a or
     * Gemini sees a different prompt with the cache-disabled rollout.
     *
     * <p>The cache-ON path (cachedContents API) FORCES static-first ordering
     * (cached prefix is prepended to the per-call user message by Gemini).
     * That's a controlled difference that takes effect only when the
     * per-task flag is flipped on; cache-OFF is bit-for-bit unchanged.
     */
    private String buildQuestionSuggestPrompt(QuestionSuggestRequest req,
                                              List<String> techCriteria,
                                              List<String> consultingCriteria,
                                              String managerStyleJson) {
        return buildQuestionSuggestDynamicPart(req)
                + buildQuestionSuggestCacheablePrefix(req.discipline, req.seniority,
                       techCriteria, consultingCriteria, managerStyleJson);
    }

    // exampleSelfCitationQuestionId removed in §2.5 — it was only used to
    // template a {questionId: "%s"} placeholder into the "Example valid JSON"
    // block, which is itself gone (schema enforces shape; the model doesn't
    // need a JSON example).

    public String buildReviewText(List<AnswerWithText> answers, String overallComment, String overallRating) {
        StringBuilder sb = new StringBuilder();
        for (var a : answers) {
            if (a.response != null && !a.response.isBlank()) {
                sb.append("Q: ").append(a.questionText).append("\n");
                sb.append("A: ").append(a.response).append("\n\n");
            }
        }
        if (overallComment != null && !overallComment.isBlank())
            sb.append("Overall Comment:\n").append(overallComment).append("\n\n");
        if (overallRating != null && !overallRating.isBlank())
            sb.append("Rating: ").append(overallRating).append("\n");
        return sb.toString().trim();
    }

    public static class AnswerWithText {
        public String questionId;
        public String questionText;
        public String response;
    }

    // GeminiResult (nested class), GEMINI_HTTP_TIMEOUT, MAX_429_RETRIES,
    // MAX_429_BACKOFF_MS — all removed in RS-1. The shared values now live
    // on GeminiClient; the local GeminiResult is replaced by the public
    // top-level com.catalystflow.ai.service.GeminiResult record.

    /**
     * Per-task output and thinking budgets for the manager flows. Returns
     * {@code [maxOutputTokens, thinkingBudget]}. Sized so the largest legitimate
     * output fits comfortably AND the 2.5-series reasoning budget can't eat
     * the visible-output budget (incident feac9e10 / 2026-05-18). Repair tasks
     * inherit the parent's budgets.
     *
     * <p>2026-05-18 (pre-demo): bumped all caps aggressively to eliminate
     * MAX_TOKENS truncation as a possible failure mode during the demo. Right-
     * size back down after telemetry shows actual p95 tokenOut per task.
     *
     * <p>2026-05-28: right-sized thinkingBudget across all three manager tasks
     * after end-to-end latency profiling on gemini-3.5-flash showed TTFT was
     * almost entirely thinking-phase (~300 tokens/sec). Output caps untouched —
     * MAX_TOKENS truncation was never the bottleneck. Approximate TTFT:
     * GOLDEN ~30s → ~10s, DIGEST ~15s → ~7s, QSUGG ~8s → ~4s. Repair tasks
     * inherit the parent's smaller budget.
     */
    private static int[] budgetsFor(String taskName) {
        return switch (taskName) {
            // Was 4096/2048 → 32768/8192 (pre-demo) → 32768/3072 (right-sized).
            // GOLDEN produces mirror-style prose across multiple sections;
            // 3k reasoning tokens covers it without the long thinking-phase
            // stall manager-side users were hitting.
            case "manager-golden", "manager-golden-repair" -> new int[]{32_768, 3_072};
            // Was 2048/1024 → 16384/4096 (pre-demo) → 16384/2048 (right-sized).
            // DIGEST clusters peer reviews into themes — moderate reasoning,
            // not deep CoT. 2k thinking is enough for the clustering work.
            case "peer-360-digest", "peer-360-digest-repair" -> new int[]{16_384, 2_048};
            // Was 1024/512 → 8192/2048 (pre-demo) → 8192/1024 (right-sized).
            // QUESTION_SUGGEST emits ~150 visible tokens; light reasoning.
            // Interactive call so TTFT matters most here.
            case "manager-question-suggest", "manager-question-suggest-repair" -> new int[]{8_192, 1_024};
            default -> new int[]{32_768, 4_096};
        };
    }

    /**
     * Thin wrapper around {@link GeminiClient#call} that resolves the per-task
     * {@code [maxOutputTokens, thinkingBudget]} via {@link #budgetsFor} so the
     * three call sites (golden, digest, suggest + their repair variants) keep
     * reading naturally. Moves the HTTP retry / 429 / finishReason / telemetry
     * logic to {@link GeminiClient}; this method is now ~5 lines instead of
     * ~170.
     */
    private GeminiResult callGemini(String taskName, String systemPrompt, String userPrompt) {
        int[] budgets = budgetsFor(taskName);
        // Map kebab-case taskName ("manager-golden" etc.) back to its canonical
        // TaskMetadata.TASK_* constant, then resolve the model.
        String model = modelResolver.modelFor(TaskMetadata.canonicalTaskType(taskName));
        return geminiClient.call(GeminiRequest.of(taskName, systemPrompt, userPrompt,
                budgets[0], budgets[1], model));
    }

    /**
     * @deprecated since RS-1 — moved to {@link GeminiClient#redactedPreview}.
     *             Forwarding wrapper retained because the existing
     *             {@code ManagerGuidanceServiceTest.redactedPreview_redactsIdsAndTruncates}
     *             reaches this static via reflection. Per RS-1 AC-1.5, existing
     *             tests must pass without modification; delete this wrapper
     *             when the test is migrated to {@code GeminiClientTest}.
     */
    @Deprecated
    private static String redactedPreview(String text) {
        return GeminiClient.redactedPreview(text);
    }

    private static String stripMarkdown(String text) {
        if (text == null) return "{}";
        String s = text.trim();
        if (s.startsWith("```")) s = s.replaceAll("^```(?:json)?\\s*", "").replaceAll("```\\s*$", "").trim();
        int start = s.indexOf('{');
        if (start > 0) s = s.substring(start);
        return s;
    }
}

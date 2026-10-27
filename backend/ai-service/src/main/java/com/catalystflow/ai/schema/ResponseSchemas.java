package com.catalystflow.ai.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central source of truth for Gemini {@code responseSchema} structures, one
 * per AI task type. Schemas are expressed as {@code Map<String, Object>}
 * because that's what Gemini's {@code generationConfig.responseSchema}
 * accepts — a subset of OpenAPI Schema.
 *
 * <p>RS-1 wired the {@code responseSchema} slot into
 * {@code com.catalystflow.ai.service.GeminiRequest} and
 * {@code GeminiClient.buildRequestBody}. RS-2..RS-6 populate it via
 * {@code ResponseSchemas.schemaFor*()}.
 *
 * <h2>Why schemas live in their own package</h2>
 * Per the RS plan AC-2.1, kept separate from the {@code service} package
 * so RS-3..RS-6 add to a single discoverable registry without coupling to
 * the HTTP layer or to any specific service.
 *
 * <h2>Why schemas live here, not on the model class</h2>
 * <ul>
 *   <li>{@code ManagerGoldenReview.validate()} is the DEFENSIVE check —
 *       catches what Gemini's decoder can't (referential integrity of
 *       {@code [cN]} tokens to the citations map, conditional required
 *       fields like {@code peer_360}-needs-authorUserId).</li>
 *   <li>{@code ResponseSchemas.schemaForManagerGolden()} is the PROACTIVE
 *       check — sent to Gemini so the decoder enforces shape at generation
 *       time, eliminating most schema-failure cases before they happen.</li>
 *   <li>Splitting them keeps the model class free of HTTP-layer concerns
 *       and lets the schema be reused across multiple AI tasks if needed.</li>
 * </ul>
 *
 * <h2>Gemini's JSON Schema 2020-12 subset (RJS-1, 2026-05-25)</h2>
 * Schemas built here are sent as {@code generationConfig.response_json_schema}
 * — the newer Gemini field that accepts full JSON Schema 2020-12 — NOT the
 * legacy {@code responseSchema} field (OpenAPI 3 subset). The wiring lives in
 * {@link com.catalystflow.ai.service.GeminiClient#buildRequestBody}.
 * <p>Supports: {@code type, format, description, nullable, enum, items,
 * properties, required, additionalProperties (as schema), minimum, maximum,
 * minItems, maxItems, minProperties, maxProperties, pattern, propertyOrdering}.
 * <p>Not yet validated in this codebase (treat as unsupported until proven):
 * {@code patternProperties, $ref, oneOf, anyOf, allOf, if/then/else}. The
 * recursive guard test {@code noSchemaContainsAnyGeminiRejectedKeyword} keeps
 * these out of any {@code schemaFor*()} output so a regression can't ship
 * silently.
 *
 * <p><strong>Historical context — 2026-05-25 hotfix → RJS-1 migration:</strong>
 * The pre-hotfix version used the OpenAPI-subset {@code response_schema}
 * field and emitted {@code additionalProperties} on the citations map.
 * Gemini rejected every generateContent call with
 * {@code 400 INVALID_ARGUMENT: Unknown name "additionalProperties" at
 * 'generation_config.response_schema.properties[N].value'}. The hotfix
 * stripped {@code additionalProperties}, which left Gemini with no
 * schema-side pressure to populate the citations map. RJS-1 (this revision)
 * switches to {@code response_json_schema} and restores
 * {@code additionalProperties} so Gemini enforces citation entry shape at
 * decode time. The post-parse {@code scrubDanglingCitations} in
 * {@code AiReviewService} + {@code ManagerGuidanceService} stays as a
 * third-line defence against future Gemini drift.
 */
public final class ResponseSchemas {

    private ResponseSchemas() {
        // Static utility — no instances.
    }

    // ── Reusable building blocks ─────────────────────────────────────────────

    /** Integer 1..5 rating — same shape used by every rating field on every task. */
    private static Map<String, Object> ratingSchema() {
        return Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", 5);
    }

    private static Map<String, Object> stringSchema() {
        return Map.of("type", "string");
    }

    /**
     * Parameterised single-citation entry schema. Different AI tasks have
     * different rules:
     * <ul>
     *   <li>MANAGER_GOLDEN: sources {@code self}/{@code peer_360}; every entry
     *       must carry {@code source, reviewId, questionId, quote} so admins
     *       can audit every claim. No {@code section} field.</li>
     *   <li>MANAGER_QUESTION_SUGGEST: sources {@code self}/{@code peer_360}/
     *       {@code peer_digest}; only {@code source, quote} are universally
     *       required (source-conditional rules — {@code self}-needs-questionId,
     *       {@code peer_360}-needs-reviewId — live in
     *       {@code ManagerGuidanceService.validateSuggestion} because Gemini's
     *       schema subset can't express {@code if/then/else}). Optional
     *       {@code section} field carries the human-readable bucket label.</li>
     * </ul>
     *
     * <p>{@code source} is constrained to the supplied enum; Gemini enforces
     * this server-side. {@code authorUserId} is always present in properties
     * but never universally required — the {@code peer_360-without-authorUserId}
     * conditional rule lives in the model's {@code validate()} method.
     *
     * @param allowedSources       enum values for {@code source}
     * @param universallyRequired  field names that every entry must include
     * @param includeSection       true to add an optional {@code section} string
     */
    private static Map<String, Object> citationEntrySchema(List<String> allowedSources,
                                                           List<String> universallyRequired,
                                                           boolean includeSection) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("source", Map.of(
                "type", "string",
                "enum", allowedSources));
        properties.put("reviewId", stringSchema());
        properties.put("questionId", stringSchema());
        properties.put("quote", stringSchema());
        if (includeSection) {
            properties.put("section", stringSchema());
        }
        properties.put("authorUserId", stringSchema());

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", universallyRequired);
        return schema;
    }

    /**
     * Dynamic-keyed citation map ({@code {"c1": {...}, "c2": {...}}}) —
     * uses {@code additionalProperties} to constrain every value to the
     * supplied citation entry shape.
     *
     * <p>RJS-1 (2026-05-25): this returns to the pre-hotfix shape because
     * we now send the schema as {@code response_json_schema} (full JSON
     * Schema 2020-12) instead of {@code responseSchema} (OpenAPI subset).
     * The newer field accepts {@code additionalProperties}, so Gemini
     * enforces every citation entry's shape server-side at decode time
     * (source enum, required fields, types) — eliminating the empty-map
     * regression observed between the 2026-05-25 hotfix and this fix.
     * Source-conditional rules ({@code peer_360} → must carry
     * {@code authorUserId}) still live in the per-model {@code validate()}
     * because Gemini's subset of JSON Schema does not yet cover
     * {@code oneOf} / {@code if/then/else} on our codebase.
     */
    private static Map<String, Object> citationsMapSchema(Map<String, Object> entrySchema) {
        return Map.of(
                "type", "object",
                "additionalProperties", entrySchema);
    }

    // ── Reusable: array-of-object shape with one required field ──────────────

    /**
     * Schema for an array whose items are objects with a single mandatory
     * string field. Used by both DIGEST themes ({@code theme}) and the
     * consensus/dissent points ({@code point}).
     *
     * @param requiredField name of the one string field that must be present
     * @param extraProps    optional additional properties (e.g. {@code reviewIds}
     *                      array). Pass {@link Map#of()} when none.
     */
    private static Map<String, Object> arrayOfObjectsSchema(String requiredField,
                                                            Map<String, Object> extraProps) {
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put(requiredField, stringSchema());
        itemProps.putAll(extraProps);

        Map<String, Object> itemSchema = new LinkedHashMap<>();
        itemSchema.put("type", "object");
        itemSchema.put("properties", itemProps);
        itemSchema.put("required", List.of(requiredField));

        return Map.of("type", "array", "items", itemSchema);
    }

    // ── RS-2: MANAGER_GOLDEN ─────────────────────────────────────────────────

    /**
     * Response schema for {@code MANAGER_GOLDEN} — matches the on-the-wire
     * shape of {@link com.catalystflow.ai.model.ManagerGoldenReview}.
     *
     * <p>Eight required content keys (5 narrative + 3 ratings) plus an
     * optional {@code citations} map. Ratings are integer-1-to-5 enforced
     * by {@code minimum}/{@code maximum} so Gemini cannot emit a string
     * rating or a 0/6/null integer.
     */
    public static Map<String, Object> schemaForManagerGolden() {
        Map<String, Object> properties = new LinkedHashMap<>();
        // LinkedHashMap so the JSON serialisation of this schema is stable
        // across builds — easier to diff in a PR and easier for Gemini to
        // honour propertyOrdering if we add it later.
        properties.put("technologyRating", ratingSchema());
        properties.put("technologyComments", stringSchema());
        properties.put("consultingRating", ratingSchema());
        properties.put("consultingComments", stringSchema());
        properties.put("companyValuesComments", stringSchema());
        properties.put("developmentOpportunities", stringSchema());
        properties.put("otherComments", stringSchema());
        properties.put("overallRating", ratingSchema());
        // RS-2 follow-up (code-review): every GOLDEN citation must carry full
        // traceability (source + reviewId + questionId + quote) so admins can
        // audit each claim. No section field on GOLDEN — admins read the prose,
        // not a section label.
        properties.put("citations", citationsMapSchema(
                citationEntrySchema(
                        List.of("self", "peer_360"),
                        List.of("source", "reviewId", "questionId", "quote"),
                        false)));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of(
                "technologyRating", "technologyComments",
                "consultingRating", "consultingComments",
                "companyValuesComments", "developmentOpportunities",
                "otherComments", "overallRating", "citations"));
        // citations is now REQUIRED (post-deploy 2026-05-25 hotfix follow-up):
        // Gemini was emitting [cN] markers inline in prose but shipping
        // citations: {} (or omitting the key entirely) when the schema didn't
        // require it, breaking citation links in the frontend and tripping
        // MANAGER_GOLDEN SCHEMA_INVALID on referential-integrity. Gemini may
        // still emit an empty {} map (which is valid here — the post-parse
        // scrub in ManagerGuidanceService strips any dangling [cN] tags whose
        // key isn't in the map). The validate() referential-integrity check
        // remains as defence-in-depth for any cases the scrub misses.
        return schema;
    }

    // ── RS-3: PEER_360_DIGEST ────────────────────────────────────────────────

    /**
     * Response schema for {@code PEER_360_DIGEST} — matches the on-the-wire
     * shape of {@link com.catalystflow.ai.model.Peer360Digest}.
     *
     * <p>Four required top-level keys ({@code themes, consensus, dissent,
     * overallSentiment}). {@code overallSentiment} is constrained to the
     * four-value enum that {@code Peer360Digest.ALLOWED_SENTIMENTS} mirrors,
     * so Gemini cannot emit a stray "neutral" or "negative".
     *
     * <p>Item-level required matches {@code Peer360Digest.validate()}:
     * themes need {@code theme}, points need {@code point}. {@code summary}
     * and {@code reviewIds} are emitted by the prompt but not strictly
     * required — RS-3 is a refactor, not a behaviour change. Tightening
     * those is a separate decision.
     *
     * <p>Citations are NOT in this schema today — the plan's RS-8 amendment
     * adds them; until then the digest is citation-free.
     */
    public static Map<String, Object> schemaForPeer360Digest() {
        Map<String, Object> reviewIdsSchema = Map.of(
                "type", "array",
                "items", stringSchema());

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("themes",
                arrayOfObjectsSchema("theme", Map.of("summary", stringSchema())));
        properties.put("consensus",
                arrayOfObjectsSchema("point", Map.of("reviewIds", reviewIdsSchema)));
        properties.put("dissent",
                arrayOfObjectsSchema("point", Map.of("reviewIds", reviewIdsSchema)));
        properties.put("overallSentiment", Map.of(
                "type", "string",
                "enum", com.catalystflow.ai.model.Peer360Digest.ALLOWED_SENTIMENTS));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("themes", "consensus", "dissent", "overallSentiment"));
        return schema;
    }

    // ── RS-5: ANALYZE ────────────────────────────────────────────────────────

    /**
     * Response schema for {@code ANALYZE} — matches the on-the-wire shape
     * of {@link com.catalystflow.ai.model.AiReviewResponse}.
     *
     * <p>One required top-level key: {@code suggestions} (array). Each item
     * is a {@code QuestionFeedback} object with required fields
     * {@code questionId, questionText, verdict, suggestions}. The
     * {@code verdict} field is constrained to the two-value enum
     * {@code [STRONG, NEEDS_IMPROVEMENT]} so Gemini cannot emit a stray
     * "GOOD" or "BAD" string.
     *
     * <p>The inner {@code suggestions} field is an array of plain strings
     * (the actionable improvement tips per question).
     *
     * <p>Pairs with {@code AiReviewService.normalizeResponse} which keeps
     * the business-rule cleanup that responseSchema can't express
     * (cap STRONG to 1 suggestion, cap NEEDS_IMPROVEMENT to 3, add a
     * canned fallback when Gemini emits an empty suggestions list).
     */
    public static Map<String, Object> schemaForAnalyze() {
        Map<String, Object> feedbackProps = new LinkedHashMap<>();
        feedbackProps.put("questionId", stringSchema());
        feedbackProps.put("questionText", stringSchema());
        feedbackProps.put("verdict", Map.of(
                "type", "string",
                "enum", List.of("STRONG", "NEEDS_IMPROVEMENT")));
        feedbackProps.put("suggestions", Map.of(
                "type", "array",
                "items", stringSchema()));

        Map<String, Object> feedbackItemSchema = new LinkedHashMap<>();
        feedbackItemSchema.put("type", "object");
        feedbackItemSchema.put("properties", feedbackProps);
        feedbackItemSchema.put("required", List.of("questionId", "questionText", "verdict", "suggestions"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("suggestions", Map.of(
                "type", "array",
                "items", feedbackItemSchema));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("suggestions"));
        return schema;
    }

    // ── RS-6: INSIGHTS ───────────────────────────────────────────────────────

    /**
     * Response schema for {@code INSIGHTS} — matches the on-the-wire shape
     * of {@link com.catalystflow.ai.model.AiReviewInsightsResponse}.
     *
     * <p>Three required top-level keys ({@code summary, goldenMirror, citations}).
     * The {@code citations} map is now REQUIRED (post-deploy 2026-05-25 hotfix
     * follow-up): Gemini was emitting empty {@code {}} citation maps (or
     * omitting the key) when the schema didn't require it, while still
     * embedding {@code [cN]} markers inline in the prose — frontend rendered
     * unclickable numbers. Making citations required forces Gemini to materialise
     * the entries; the post-parse scrub in {@link com.catalystflow.ai.service.AiReviewService}
     * strips any dangling {@code [cN]} tags whose key is missing from the map
     * (e.g. when Gemini still emits {@code citations: {}} for a citation-free
     * narrative). {@code validateInsightsResponse} catches the blank-string
     * case that responseSchema can't cheaply express.
     *
     * <p>Citation entries accept only {@code source: "self"} — INSIGHTS sees
     * the employee's own answers; there is no peer dimension to cite. Every
     * citation requires {@code source, reviewId, questionId, quote} per the
     * prompt's "MUST contain" rules — quote is a verbatim substring (≤ 15
     * words) used by the frontend to highlight the source span on click.
     *
     * <p>Pairs with the {@code coerceStringField} safety net in
     * {@code parseInsightsResponse} which patches over the historical
     * array-vs-string drift on {@code summary}/{@code goldenMirror}.
     * Post-RS-6 that safety net should never fire — its WARN log is the
     * production canary for a Gemini schema-enforcement gap.
     */
    public static Map<String, Object> schemaForInsights() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("summary", stringSchema());
        properties.put("goldenMirror", stringSchema());
        properties.put("citations", citationsMapSchema(
                citationEntrySchema(
                        List.of("self"),
                        List.of("source", "reviewId", "questionId", "quote"),
                        false)));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("summary", "goldenMirror", "citations"));
        return schema;
    }

    // ── RS-4: MANAGER_QUESTION_SUGGEST ───────────────────────────────────────

    /**
     * Response schema for {@code MANAGER_QUESTION_SUGGEST} — matches the
     * on-the-wire shape of
     * {@link com.catalystflow.ai.service.ManagerGuidanceService.SuggestionResult}
     * (excluding the {@code @JsonIgnore} telemetry fields).
     *
     * <p>Three required keys at the top level: {@code isGoodAnswer} boolean,
     * {@code suggestion} string, {@code citations} object. {@code citations}
     * must be present even when empty ({@code {}}) so the frontend can rely
     * on a stable shape.
     *
     * <p>Citation entries accept three sources ({@code self, peer_360,
     * peer_digest}) because question-suggest reads the peer-360 digest as
     * one of its evidence sources. Universally required: only
     * {@code source} + {@code quote} — the source-conditional rules
     * ({@code self}-needs-questionId, {@code peer_360}-needs-reviewId,
     * {@code self}-questionId-must-be-allowed) live in
     * {@code ManagerGuidanceService.validateSuggestion} because Gemini's
     * OpenAPI subset cannot express {@code if/then/else}.
     *
     * <p>The optional {@code section} field carries a human-readable bucket
     * label (e.g. "Stakeholder collaboration") that the frontend uses for
     * the citation chip tooltip.
     */
    public static Map<String, Object> schemaForQuestionSuggest() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("isGoodAnswer", Map.of("type", "boolean"));
        properties.put("suggestion", stringSchema());
        properties.put("citations", citationsMapSchema(
                citationEntrySchema(
                        List.of("self", "peer_360", "peer_digest"),
                        List.of("source", "quote"),
                        true)));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("isGoodAnswer", "suggestion", "citations"));
        return schema;
    }
}

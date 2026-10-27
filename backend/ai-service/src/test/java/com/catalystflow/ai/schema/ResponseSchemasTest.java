package com.catalystflow.ai.schema;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the actual shape of every {@link ResponseSchemas} method. The
 * per-service schema tests already verify "the schema was passed" via
 * ArgumentCaptor + {@code assertEquals(captured, ResponseSchemas.schemaForX())} —
 * that's a tautology: it catches call-site omission but not a bad schema
 * implementation. This class catches the second class of bug.
 *
 * <p>Asserts:
 * <ul>
 *   <li>Required field lists exactly</li>
 *   <li>Enum values exactly</li>
 *   <li>Top-level type / structure</li>
 *   <li>Property type assignments (e.g. ratings are integer-with-bounds)</li>
 * </ul>
 *
 * <p>Schemas are returned as {@code Map<String, Object>} so tests use
 * map-key assertions. {@code LinkedHashMap} insertion order is part of the
 * contract (ensures stable JSON serialisation across builds + sets up
 * {@code propertyOrdering} for any future Gemini-side use).
 */
class ResponseSchemasTest {

    // ── MANAGER_GOLDEN ───────────────────────────────────────────────────────

    @Test
    void schemaForManagerGolden_topLevelShape() {
        Map<String, Object> s = ResponseSchemas.schemaForManagerGolden();
        assertEquals("object", s.get("type"));
        assertEquals(List.of(
                "technologyRating", "technologyComments",
                "consultingRating", "consultingComments",
                "companyValuesComments", "developmentOpportunities",
                "otherComments", "overallRating", "citations"),
                s.get("required"),
                "9 required content keys (5 narrative + 3 ratings + citations); citations "
                + "promoted to required (2026-05-25 hotfix follow-up) — Gemini was omitting "
                + "the map when not required, breaking the [cN] → entry link. ManagerGuidanceService "
                + "scrubs any [cN] tag with no matching entry before validate().");
    }

    @Test
    void schemaForManagerGolden_ratingFieldsAreIntegerOneToFive() {
        Map<String, Object> props = props(ResponseSchemas.schemaForManagerGolden());
        for (String ratingField : List.of("technologyRating", "consultingRating", "overallRating")) {
            Map<String, Object> rating = mapAt(props, ratingField);
            assertEquals("integer", rating.get("type"), ratingField + " must be integer");
            assertEquals(1, rating.get("minimum"), ratingField + " minimum is 1");
            assertEquals(5, rating.get("maximum"), ratingField + " maximum is 5");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaForManagerGolden_citations_constrainsEntryShape_viaAdditionalProperties() {
        // RJS-1 (2026-05-25): schemas are now sent as `response_json_schema`
        // (full JSON Schema 2020-12) — additionalProperties is supported and
        // enforced server-side by Gemini at decode time. We re-introduced it
        // after the 2026-05-25 hotfix had to strip it for the legacy
        // `responseSchema` field. The entry shape (source enum, required
        // fields) is now enforced both server-side (here) and post-parse
        // (ManagerGoldenReview.validate() — defense in depth).
        Map<String, Object> props = props(ResponseSchemas.schemaForManagerGolden());
        Map<String, Object> citations = mapAt(props, "citations");
        assertEquals("object", citations.get("type"));
        Map<String, Object> entrySchema = (Map<String, Object>) citations.get("additionalProperties");
        assertNotNull(entrySchema, "citations.additionalProperties must constrain entry shape");
        assertEquals("object", entrySchema.get("type"));
        assertEquals(List.of("source", "reviewId", "questionId", "quote"),
                entrySchema.get("required"),
                "GOLDEN citation: source+reviewId+questionId+quote required for admin auditing");
        Map<String, Object> sourceProp = mapAt(props(entrySchema), "source");
        assertEquals(List.of("self", "peer_360"), sourceProp.get("enum"),
                "GOLDEN sources: self, peer_360 only — no peer_digest");
    }

    // ── PEER_360_DIGEST ──────────────────────────────────────────────────────

    @Test
    void schemaForPeer360Digest_topLevelShape() {
        Map<String, Object> s = ResponseSchemas.schemaForPeer360Digest();
        assertEquals("object", s.get("type"));
        assertEquals(List.of("themes", "consensus", "dissent", "overallSentiment"),
                s.get("required"),
                "4 required top-level keys");
    }

    @Test
    void schemaForPeer360Digest_overallSentimentEnum() {
        Map<String, Object> props = props(ResponseSchemas.schemaForPeer360Digest());
        Map<String, Object> sentiment = mapAt(props, "overallSentiment");
        assertEquals("string", sentiment.get("type"));
        // Must mirror Peer360Digest.ALLOWED_SENTIMENTS exactly — drift means
        // Gemini could legitimately emit a value the validate() rejects.
        assertEquals(com.catalystflow.ai.model.Peer360Digest.ALLOWED_SENTIMENTS,
                sentiment.get("enum"),
                "sentiment enum must mirror Peer360Digest.ALLOWED_SENTIMENTS");
    }

    @Test
    void schemaForPeer360Digest_themesItemRequiresThemeField() {
        Map<String, Object> props = props(ResponseSchemas.schemaForPeer360Digest());
        Map<String, Object> themesArr = mapAt(props, "themes");
        assertEquals("array", themesArr.get("type"));
        Map<String, Object> item = (Map<String, Object>) themesArr.get("items");
        assertEquals("object", item.get("type"));
        assertEquals(List.of("theme"), item.get("required"),
                "themes item: only 'theme' is required (matches Peer360Digest.validate())");
    }

    @Test
    void schemaForPeer360Digest_consensusAndDissentItemsRequirePoint() {
        Map<String, Object> props = props(ResponseSchemas.schemaForPeer360Digest());
        for (String field : List.of("consensus", "dissent")) {
            Map<String, Object> arr = mapAt(props, field);
            assertEquals("array", arr.get("type"), field + " must be array");
            Map<String, Object> item = (Map<String, Object>) arr.get("items");
            assertEquals(List.of("point"), item.get("required"),
                    field + " item: only 'point' is required");
        }
    }

    // ── MANAGER_QUESTION_SUGGEST ─────────────────────────────────────────────

    @Test
    void schemaForQuestionSuggest_topLevelShape() {
        Map<String, Object> s = ResponseSchemas.schemaForQuestionSuggest();
        assertEquals("object", s.get("type"));
        assertEquals(List.of("isGoodAnswer", "suggestion", "citations"), s.get("required"),
                "3 required top-level keys — citations required even when empty {}");
    }

    @Test
    void schemaForQuestionSuggest_isGoodAnswerIsBoolean() {
        Map<String, Object> props = props(ResponseSchemas.schemaForQuestionSuggest());
        Map<String, Object> isGood = mapAt(props, "isGoodAnswer");
        assertEquals("boolean", isGood.get("type"),
                "Required-shape contract: isGoodAnswer is boolean, not string/null");
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaForQuestionSuggest_citations_constrainsEntryShape_viaAdditionalProperties() {
        // RJS-1 (2026-05-25) — see schemaForManagerGolden sibling test.
        // QUESTION_SUGGEST has wider source enum (incl. peer_digest) and a
        // looser universally-required set (only source+quote); source-
        // conditional rules (self→questionId, peer_360→reviewId) still live
        // in ManagerGuidanceService.validateSuggestion because Gemini's
        // accepted subset of JSON Schema doesn't yet cover if/then/else.
        Map<String, Object> citations = mapAt(props(ResponseSchemas.schemaForQuestionSuggest()),
                "citations");
        assertEquals("object", citations.get("type"));
        Map<String, Object> entry = (Map<String, Object>) citations.get("additionalProperties");
        assertNotNull(entry, "citations.additionalProperties must constrain entry shape");
        Map<String, Object> sourceProp = mapAt(props(entry), "source");
        assertEquals(List.of("self", "peer_360", "peer_digest"), sourceProp.get("enum"),
                "QUESTION_SUGGEST accepts the third source — peer_digest");
        assertTrue(props(entry).containsKey("section"),
                "QUESTION_SUGGEST citation includes the optional section field");
        assertEquals(List.of("source", "quote"), entry.get("required"),
                "QUESTION_SUGGEST: only source+quote universally required");
    }

    // ── ANALYZE (RS-5) ───────────────────────────────────────────────────────

    @Test
    void schemaForAnalyze_topLevelShape() {
        Map<String, Object> s = ResponseSchemas.schemaForAnalyze();
        assertEquals("object", s.get("type"));
        assertEquals(List.of("suggestions"), s.get("required"),
                "single required key: suggestions array");
        Map<String, Object> arr = mapAt(props(s), "suggestions");
        assertEquals("array", arr.get("type"));
    }

    @Test
    void schemaForAnalyze_feedbackItem_requiresAllFourFields() {
        Map<String, Object> arr = mapAt(props(ResponseSchemas.schemaForAnalyze()), "suggestions");
        Map<String, Object> item = (Map<String, Object>) arr.get("items");
        assertEquals("object", item.get("type"));
        assertEquals(List.of("questionId", "questionText", "verdict", "suggestions"),
                item.get("required"),
                "Strict — schema enforces all 4 fields server-side, eliminating "
                + "normalizeResponse's missing-field defaults from the happy path");
    }

    @Test
    void schemaForAnalyze_verdictEnumIsStrongOrNeedsImprovement() {
        Map<String, Object> arr = mapAt(props(ResponseSchemas.schemaForAnalyze()), "suggestions");
        Map<String, Object> item = (Map<String, Object>) arr.get("items");
        Map<String, Object> verdict = mapAt(props(item), "verdict");
        assertEquals("string", verdict.get("type"));
        assertEquals(List.of("STRONG", "NEEDS_IMPROVEMENT"), verdict.get("enum"),
                "Verdict enum must mirror AiReviewQuestionFeedback.VerdictEnum");
    }

    @Test
    void schemaForAnalyze_innerSuggestionsIsArrayOfStrings() {
        Map<String, Object> arr = mapAt(props(ResponseSchemas.schemaForAnalyze()), "suggestions");
        Map<String, Object> item = (Map<String, Object>) arr.get("items");
        Map<String, Object> innerArr = mapAt(props(item), "suggestions");
        assertEquals("array", innerArr.get("type"));
        Map<String, Object> innerItem = (Map<String, Object>) innerArr.get("items");
        assertEquals("string", innerItem.get("type"));
    }

    // ── INSIGHTS (RS-6) ──────────────────────────────────────────────────────

    @Test
    void schemaForInsights_topLevelShape() {
        Map<String, Object> s = ResponseSchemas.schemaForInsights();
        assertEquals("object", s.get("type"));
        assertEquals(List.of("summary", "goldenMirror", "citations"), s.get("required"),
                "citations promoted to required (2026-05-25 hotfix follow-up) — Gemini was "
                + "emitting empty {} citation maps while still embedding [cN] markers inline. "
                + "parseInsightsResponse scrubs any dangling [cN] tags whose key is missing.");
        for (String field : List.of("summary", "goldenMirror")) {
            assertEquals("string", mapAt(props(s), field).get("type"),
                    field + " must be string (anti-array-shape per RS-6)");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaForInsights_citations_constrainsEntryShape_viaAdditionalProperties() {
        // RJS-1 (2026-05-25) — see schemaForManagerGolden sibling test.
        // INSIGHTS sees only the employee's own answers, so source enum is
        // 'self' only. Every entry must carry full traceability per the
        // prompt's MUST rules.
        Map<String, Object> citations = mapAt(props(ResponseSchemas.schemaForInsights()),
                "citations");
        assertEquals("object", citations.get("type"));
        Map<String, Object> entry = (Map<String, Object>) citations.get("additionalProperties");
        assertNotNull(entry, "citations.additionalProperties must constrain entry shape");
        Map<String, Object> sourceProp = mapAt(props(entry), "source");
        assertEquals(List.of("self"), sourceProp.get("enum"),
                "INSIGHTS sees only employee answers — source enum is 'self' only");
        assertEquals(List.of("source", "reviewId", "questionId", "quote"),
                entry.get("required"),
                "Every INSIGHTS citation must carry full traceability per the prompt's MUST rules");
    }

    // ── Global Gemini-subset guard ───────────────────────────────────────────

    /**
     * Walks every schemaFor*() output recursively and fails if any node
     * contains a JSON-Schema keyword that we have NOT yet validated against
     * Gemini's accepted subset. The list reflects what Gemini's response_json_schema
     * field (RJS-1, 2026-05-25) is known to reject or has not yet been tested
     * against in our codebase. Keeps a regression where a developer adds an
     * untested keyword from leaking to production.
     *
     * <p>RJS-1 removed {@code additionalProperties} from this list because
     * the migration to {@code response_json_schema} means Gemini now accepts
     * it (and we use it on the citations map). If a future model version
     * proves out additional keywords (e.g. {@code oneOf}), remove the
     * keyword here AND update the class Javadoc on {@link ResponseSchemas} —
     * both must stay in sync.
     */
    @Test
    void noSchemaContainsAnyGeminiRejectedKeyword() {
        List<String> rejected = List.of(
                "patternProperties",
                "$ref", "oneOf", "anyOf", "allOf",
                "if", "then", "else");
        List<Map<String, Object>> allSchemas = List.of(
                ResponseSchemas.schemaForAnalyze(),
                ResponseSchemas.schemaForInsights(),
                ResponseSchemas.schemaForManagerGolden(),
                ResponseSchemas.schemaForQuestionSuggest(),
                ResponseSchemas.schemaForPeer360Digest());
        for (Map<String, Object> schema : allSchemas) {
            for (String keyword : rejected) {
                String hit = findKeyRecursive(schema, keyword, "");
                assertNull(hit,
                        "Untested-against-Gemini keyword '" + keyword + "' found at path '"
                        + hit + "'. Gemini's response_json_schema field may reject or "
                        + "silently ignore it; prove acceptance with a dev smoke before "
                        + "removing the keyword from this guard.");
            }
        }
    }

    /**
     * @return dotted JSON path to the first occurrence of {@code key}, or
     *         {@code null} if absent. Walks Maps and Lists; ignores non-Map,
     *         non-List values.
     */
    @SuppressWarnings("unchecked")
    private static String findKeyRecursive(Object node, String key, String path) {
        if (node instanceof Map<?, ?> m) {
            if (m.containsKey(key)) return path + "/" + key;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String hit = findKeyRecursive(e.getValue(), key, path + "/" + e.getKey());
                if (hit != null) return hit;
            }
        } else if (node instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                String hit = findKeyRecursive(l.get(i), key, path + "[" + i + "]");
                if (hit != null) return hit;
            }
        }
        return null;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> props(Map<String, Object> schemaOrSubSchema) {
        Object p = schemaOrSubSchema.get("properties");
        assertInstanceOf(Map.class, p, "expected properties to be a Map");
        return (Map<String, Object>) p;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapAt(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        assertNotNull(v, "expected key '" + key + "' in: " + parent.keySet());
        assertInstanceOf(Map.class, v, "expected '" + key + "' value to be a Map");
        return (Map<String, Object>) v;
    }
}

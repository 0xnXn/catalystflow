package com.catalystflow.ai.model;

import java.util.List;

/**
 * Thrown when an AI response fails schema validation. The worker translates
 * this into a FAILED job row with errorCode=SCHEMA_INVALID; the frontend
 * renders the ADR-5 fallback UI.
 *
 * <p>Carries Gemini token usage and repair-retry telemetry so the FAILED
 * row attribution stays accurate even when the result wrapper is discarded.
 * Same pattern as {@link com.catalystflow.ai.service.GeminiTelemetryException}
 * for finishReason failures (TRUNCATED / SAFETY / BLOCKED).
 *
 * <p>Pre-RS-2 this exception was thrown ONLY after the validate→repair→
 * validate→throw loop (hence the "repair retry" wording in older callers).
 * Post-RS-2 it fires immediately on the first call when Gemini's
 * responseSchema-enforced output still trips the defensive {@code validate()}
 * check (referential integrity, conditional peer_360 author rules). In that
 * case {@link #repairRetries} is {@code 0}.
 */
public class SchemaInvalidException extends RuntimeException {
    private final List<String> errors;
    private final String rawOutput;

    /**
     * Gemini usageMetadata.promptTokenCount captured before the throw. Nullable
     * when the exception is constructed outside an AI flow (legacy callers).
     */
    public final Integer tokenIn;

    /** Gemini usageMetadata.candidatesTokenCount captured before the throw. Nullable. */
    public final Integer tokenOut;

    /**
     * Number of repair retries actually performed before giving up. Post-RS-2
     * this is always {@code 0} for the MANAGER_GOLDEN path; legacy paths that
     * still run the repair loop may persist {@code 1}. A persisted value of
     * {@code 1} on the MANAGER_GOLDEN path is alarm-worthy — it means the
     * loop regressed back into existence.
     */
    public final Integer repairRetries;

    /** Gemini model identifier the failed call targeted. Nullable for legacy paths. */
    public final String modelUsed;

    /**
     * CC-5 (AC-11.1): cache attribution for the failed call. When the Gemini
     * response was schema-invalid we still paid for the input tokens
     * (including cached prefix tokens if any); persisting these on the
     * FAILED row keeps cost attribution accurate. Nullable — null for
     * non-cached calls and legacy paths.
     */
    public final Boolean cacheHit;
    public final Integer cachedTokens;

    /**
     * Legacy constructor — kept for backward compatibility with callers that
     * don't have telemetry to attach. Telemetry fields are {@code null}.
     *
     * @deprecated Since RS-3. Use the 8-arg constructor in any new code so
     *             the FAILED job row carries token attribution and the
     *             repair-retry alarm signal works. AiReviewService.runJobInProcess
     *             persists null telemetry as absent attributes — indistinguishable
     *             from "no Gemini call happened", which loses cost visibility.
     */
    @Deprecated
    public SchemaInvalidException(List<String> errors, String rawOutput) {
        this(errors, rawOutput, null, null, null, null, null, null);
    }

    /**
     * 6-arg constructor (pre-CC-5) — delegates to the 8-arg form with cache
     * attribution null. Kept so existing callers compile; new code should
     * use the 8-arg form when GeminiResult is in scope.
     */
    public SchemaInvalidException(List<String> errors, String rawOutput,
                                  Integer tokenIn, Integer tokenOut,
                                  Integer repairRetries, String modelUsed) {
        this(errors, rawOutput, tokenIn, tokenOut, repairRetries, modelUsed, null, null);
    }

    /**
     * CC-5 follow-up — full constructor including cache attribution. Used by
     * {@code ManagerGuidanceService} throw sites so the FAILED AiJobs row
     * records whether the schema-invalid call was cached and how many tokens
     * came from the cached prefix.
     */
    public SchemaInvalidException(List<String> errors, String rawOutput,
                                  Integer tokenIn, Integer tokenOut,
                                  Integer repairRetries, String modelUsed,
                                  Boolean cacheHit, Integer cachedTokens) {
        super("Schema validation failed: " + String.join("; ", errors));
        this.errors = errors;
        this.rawOutput = rawOutput;
        this.tokenIn = tokenIn;
        this.tokenOut = tokenOut;
        this.repairRetries = repairRetries;
        this.modelUsed = modelUsed;
        this.cacheHit = cacheHit;
        this.cachedTokens = cachedTokens;
    }

    public List<String> getErrors() {
        return errors;
    }

    public String getRawOutput() {
        return rawOutput;
    }
}

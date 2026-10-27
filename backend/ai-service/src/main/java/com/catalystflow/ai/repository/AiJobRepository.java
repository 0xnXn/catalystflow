package com.catalystflow.ai.repository;

import com.catalystflow.ai.model.AiReviewJobStatus;
import com.catalystflow.ai.model.AiReviewStatusResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.util.Map;

@ApplicationScoped
public class AiJobRepository {

    private static final Logger LOG = Logger.getLogger(AiJobRepository.class);

    @Inject
    DynamoDbClient dynamoDb;

    @ConfigProperty(name = "ai-jobs.table-name")
    String tableName;

    /**
     * Retention (days) applied to a job's TTL when it COMPLETES, replacing the
     * short ~7-day creation TTL so a finalized review keeps its AI well past any
     * realistic revisit (no silent Gemini re-fire). PENDING/FAILED jobs keep the
     * short creation TTL and self-clean. Bounded (not infinite) so abandoned /
     * regenerated drafts still expire eventually; tunable per environment.
     */
    @ConfigProperty(name = "ai-jobs.completed-ttl-days", defaultValue = "365")
    long completedTtlDays;

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(String.valueOf(value)).build();
    }

    public void create(String jobId, String requestJson, long expiresAt) {
        create(jobId, "ANALYZE", requestJson, expiresAt);
    }

    public void create(String jobId, String taskType, String requestJson, long expiresAt) {
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "jobId", s(jobId),
                        "status", s("PENDING"),
                        "taskType", s(taskType),
                        "requestJson", s(requestJson),
                        "expiresAt", n(expiresAt)
                ))
                .build());
    }

    /**
     * (subject, cycle)-indexed create — used by PEER_360_DIGEST so the digest can
     * be resolved later by (subject, cycle) instead of being pinned to a review
     * row. In addition to the base fields it stamps {@code subjectUserId},
     * {@code cycleId}, {@code createdAt}, and the GSI hash key
     * {@code digestKey = subjectUserId#cycleId}. {@code digestKey} is written ONLY
     * here, so the {@code Peer360DigestIndex} GSI stays sparse (digest rows only).
     */
    public void createForDigest(String jobId, String taskType, String requestJson, long expiresAt,
                                String subjectUserId, String cycleId, long createdAt) {
        Map<String, AttributeValue> item = new java.util.HashMap<>();
        item.put("jobId", s(jobId));
        item.put("status", s("PENDING"));
        item.put("taskType", s(taskType));
        item.put("requestJson", s(requestJson));
        item.put("expiresAt", n(expiresAt));
        item.put("createdAt", n(createdAt));
        item.put("subjectUserId", s(subjectUserId));
        item.put("cycleId", s(cycleId));
        item.put("digestKey", s(subjectUserId + "#" + cycleId));
        dynamoDb.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
    }

    /**
     * Resolve the most recent COMPLETED {@code PEER_360_DIGEST} job for a
     * (subject, cycle) via the {@code Peer360DigestIndex} GSI — so the frontend
     * can reuse a finished digest on refresh without a review-stored pointer.
     * Queries newest-first by {@code createdAt} and filters {@code status=COMPLETED},
     * so an in-flight or failed latest attempt is skipped for the last good one.
     * Returns {@code null} when no completed digest exists. Bounded scan (Limit 10):
     * realistically a (subject, cycle) has a handful of attempts.
     */
    public String latestCompletedDigest(String subjectUserId, String cycleId) {
        QueryResponse resp = dynamoDb.query(QueryRequest.builder()
                .tableName(tableName)
                .indexName("Peer360DigestIndex")
                .keyConditionExpression("digestKey = :k")
                .filterExpression("#st = :completed")
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(Map.of(
                        ":k", s(subjectUserId + "#" + cycleId),
                        ":completed", s("COMPLETED")))
                .scanIndexForward(false)   // newest createdAt first
                .limit(10)
                .build());
        for (Map<String, AttributeValue> item : resp.items()) {
            if (item.containsKey("jobId")) {
                return item.get("jobId").s();
            }
        }
        return null;
    }

    /**
     * Atomically transition PENDING → PROCESSING. Returns true on success, false if
     * another worker already claimed the job (race-condition guard against SQS
     * duplicate delivery). Callers must check the return value before doing real
     * work — running Gemini twice for the same jobId burns quota for nothing.
     *
     * <p>Implementation: DDB conditional update with {@code status = :pending}.
     * Throws nothing on conditional failure — distinguishes "row not in expected
     * state" from real infrastructure errors which still bubble up.
     */
    public boolean tryClaim(String jobId) {
        try {
            dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("jobId", s(jobId)))
                    .updateExpression("SET #st = :processing")
                    .conditionExpression("#st = :pending")
                    .expressionAttributeNames(Map.of("#st", "status"))
                    .expressionAttributeValues(Map.of(
                            ":processing", s("PROCESSING"),
                            ":pending", s("PENDING")
                    ))
                    .build());
            return true;
        } catch (software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException e) {
            LOG.infof("Job %s already claimed (status != PENDING); skipping", jobId);
            return false;
        }
    }

    /**
     * Unconditionally writes PROCESSING. Retained for the in-process fallback path
     * which doesn't share state across workers and so doesn't need claim semantics.
     */
    public void updateProcessing(String jobId) {
        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("jobId", s(jobId)))
                .updateExpression("SET #st = :status")
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(Map.of(":status", s("PROCESSING")))
                .build());
    }

    public void updateCompleted(String jobId, String resultJson) {
        updateCompleted(jobId, resultJson, null, null, null, null, null);
    }

    /**
     * Completes the job AND persists Gemini telemetry for cost / quality tracking.
     *
     * <p>Null token values are OMITTED from the update rather than written as DDB
     * NULL, so a downstream query can tell "telemetry not captured" (attribute
     * absent — the legacy or fallback path ran) from "captured, value was 0"
     * (attribute present with N=0). Same applies to {@code modelUsed},
     * {@code promptVersion}, {@code repairRetries}.
     */
    public void updateCompleted(String jobId, String resultJson,
                                Integer tokenIn, Integer tokenOut,
                                String modelUsed, String promptVersion,
                                Integer repairRetries) {
        // Delegate to the CC-5 9-arg form with cache attribution null —
        // legacy callers (pre-cache wiring) don't have cacheHit/cachedTokens
        // and null = "attribute absent" is the right column state for them.
        updateCompleted(jobId, resultJson, tokenIn, tokenOut, modelUsed,
                promptVersion, repairRetries, null, null);
    }

    /**
     * CC-5 (AC-11.1): completes the job AND persists cache attribution
     * ({@code cacheHit}, {@code cachedTokens}) alongside the existing Gemini
     * telemetry. Same null-omission rule — nulls become absent DDB attributes.
     *
     * <p>{@code cacheHit} = {@code true} iff the {@code generateContent} call
     * used a Gemini context cache (request body included {@code cachedContent:
     * <name>}). {@code cachedTokens} = portion of {@code tokenIn} that was
     * served from cache — read from
     * {@code usageMetadata.cachedContentTokenCount} on the response. Both
     * sourced from {@link com.catalystflow.ai.service.GeminiResult}.
     *
     * <p>Persisted on AiJobs so post-hoc queries can compute hit-rate by
     * promptVersion / model / time without scraping CloudWatch — useful for
     * Athena slice-and-dice and for the CC-6 admin list endpoint's "show me
     * cached vs uncached call distribution".
     */
    public void updateCompleted(String jobId, String resultJson,
                                Integer tokenIn, Integer tokenOut,
                                String modelUsed, String promptVersion,
                                Integer repairRetries,
                                Boolean cacheHit, Integer cachedTokens) {
        StringBuilder expr = new StringBuilder("SET #st = :status, resultJson = :result");
        Map<String, AttributeValue> values = new java.util.HashMap<>();
        values.put(":status", s("COMPLETED"));
        values.put(":result", s(resultJson));
        // Extend the TTL on completion: a successful result must outlive the short
        // ~7-day creation TTL so a finalized review's AI survives later revisits
        // without re-firing Gemini. Only COMPLETED jobs get this; PENDING/FAILED
        // keep the short creation TTL and self-clean. Guard against a non-positive
        // config — a misconfigured 0 must NOT set expiresAt=now (immediate delete);
        // it leaves the creation TTL untouched instead.
        if (completedTtlDays > 0) {
            expr.append(", expiresAt = :exp");
            values.put(":exp", n(java.time.Instant.now().getEpochSecond() + completedTtlDays * 86_400L));
        }
        if (tokenIn != null) {
            expr.append(", tokenIn = :tokenIn");
            values.put(":tokenIn", n(tokenIn));
        }
        if (tokenOut != null) {
            expr.append(", tokenOut = :tokenOut");
            values.put(":tokenOut", n(tokenOut));
        }
        if (modelUsed != null && !modelUsed.isBlank()) {
            expr.append(", modelUsed = :modelUsed");
            values.put(":modelUsed", s(modelUsed));
        }
        if (promptVersion != null && !promptVersion.isBlank()) {
            expr.append(", promptVersion = :promptVersion");
            values.put(":promptVersion", s(promptVersion));
        }
        if (repairRetries != null) {
            expr.append(", repairRetries = :repairRetries");
            values.put(":repairRetries", n(repairRetries));
        }
        if (cacheHit != null) {
            expr.append(", cacheHit = :cacheHit");
            values.put(":cacheHit", AttributeValue.builder().bool(cacheHit).build());
        }
        if (cachedTokens != null) {
            expr.append(", cachedTokens = :cachedTokens");
            values.put(":cachedTokens", n(cachedTokens));
        }
        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("jobId", s(jobId)))
                .updateExpression(expr.toString())
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(values)
                .build());
    }

    public void updateFailed(String jobId, String errorMessage) {
        updateFailed(jobId, errorMessage, null, null, null, null);
    }

    /**
     * Marks the job FAILED and persists Gemini telemetry from the failed run.
     *
     * <p>Truncation and safety-block failures still cost real money — Gemini
     * bills for all tokens generated (visible + thinking). Capturing token
     * counts on the FAILED row keeps cost attribution accurate. Same
     * null-omission rule as {@link #updateCompleted}.
     */
    public void updateFailed(String jobId, String errorMessage,
                             Integer tokenIn, Integer tokenOut,
                             String modelUsed, String promptVersion) {
        // Delegate to the 7-arg form with repairRetries=null. Existing callers
        // (truncation / rate-limit / generic failure paths) don't have repair
        // semantics, so null = "attribute absent" is the right column state.
        updateFailed(jobId, errorMessage, tokenIn, tokenOut, modelUsed,
                promptVersion, null);
    }

    /**
     * Marks the job FAILED and persists Gemini telemetry plus the
     * repair-retry count from the failed run.
     *
     * <p>Added in RS-2 follow-up so {@link com.catalystflow.ai.model.SchemaInvalidException}
     * can persist {@code repairRetries=0} on schema-failed rows. Without it,
     * the column would always be absent on FAILED rows and the
     * "post-RS-2 repairRetries should never be 1" alarm signal would be
     * impossible to detect — every row would read the same as a regression.
     *
     * <p>Same null-omission rule as the 6-arg variant: nulls become absent
     * attributes, NOT NULL-typed values. Downstream queries can tell
     * "telemetry not captured" (attribute absent) from "captured, value was 0"
     * (attribute present with N=0).
     */
    public void updateFailed(String jobId, String errorMessage,
                             Integer tokenIn, Integer tokenOut,
                             String modelUsed, String promptVersion,
                             Integer repairRetries) {
        // Delegate to the CC-5 9-arg form. Failed jobs typically don't carry
        // cache attribution (the call may have failed BEFORE cache resolution,
        // or the cache layer may have degraded to uncached anyway), but the
        // 9-arg form is available for callers that DO have cacheHit on the
        // throw path — e.g. SchemaInvalidException paths in the service layer
        // could plumb it through in the future.
        updateFailed(jobId, errorMessage, tokenIn, tokenOut, modelUsed,
                promptVersion, repairRetries, null, null);
    }

    /**
     * CC-5 (AC-11.1) FAILED-row variant — mirror of the COMPLETED 9-arg
     * overload. Persists {@code cacheHit} + {@code cachedTokens} alongside
     * the existing telemetry. Same null-omission rule: nulls become absent
     * attributes so a downstream query can tell "not captured" from
     * "captured, value was 0".
     */
    public void updateFailed(String jobId, String errorMessage,
                             Integer tokenIn, Integer tokenOut,
                             String modelUsed, String promptVersion,
                             Integer repairRetries,
                             Boolean cacheHit, Integer cachedTokens) {
        StringBuilder expr = new StringBuilder("SET #st = :status, errorMessage = :err");
        Map<String, AttributeValue> values = new java.util.HashMap<>();
        values.put(":status", s("FAILED"));
        values.put(":err", s(errorMessage != null ? errorMessage : "Unknown error"));
        if (tokenIn != null) {
            expr.append(", tokenIn = :tokenIn");
            values.put(":tokenIn", n(tokenIn));
        }
        if (tokenOut != null) {
            expr.append(", tokenOut = :tokenOut");
            values.put(":tokenOut", n(tokenOut));
        }
        if (modelUsed != null && !modelUsed.isBlank()) {
            expr.append(", modelUsed = :modelUsed");
            values.put(":modelUsed", s(modelUsed));
        }
        if (promptVersion != null && !promptVersion.isBlank()) {
            expr.append(", promptVersion = :promptVersion");
            values.put(":promptVersion", s(promptVersion));
        }
        if (repairRetries != null) {
            expr.append(", repairRetries = :repairRetries");
            values.put(":repairRetries", n(repairRetries));
        }
        if (cacheHit != null) {
            expr.append(", cacheHit = :cacheHit");
            values.put(":cacheHit", AttributeValue.builder().bool(cacheHit).build());
        }
        if (cachedTokens != null) {
            expr.append(", cachedTokens = :cachedTokens");
            values.put(":cachedTokens", n(cachedTokens));
        }
        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("jobId", s(jobId)))
                .updateExpression(expr.toString())
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(values)
                .build());
    }

    public AiReviewStatusResponse get(String jobId) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("jobId", s(jobId)))
                .build());

        if (!response.hasItem() || response.item().isEmpty()) {
            return null;
        }

        Map<String, AttributeValue> item = response.item();
        AiReviewStatusResponse status = new AiReviewStatusResponse();
        status.setJobId(jobId);
        status.setStatus(AiReviewJobStatus.fromValue(item.get("status").s()));

        if (item.containsKey("errorMessage")) {
            status.setErrorMessage(item.get("errorMessage").s());
        }
        return status;
    }

    public String getRequestJson(String jobId) {
        // Consistent read ensures the item written by startAnalysis is visible
        // to the async path that may call this immediately after creation.
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("jobId", s(jobId)))
                .projectionExpression("requestJson")
                .consistentRead(true)
                .build());

        if (!response.hasItem() || !response.item().containsKey("requestJson")) {
            return null;
        }
        return response.item().get("requestJson").s();
    }

    public String getTaskType(String jobId) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("jobId", s(jobId)))
                .projectionExpression("taskType")
                .consistentRead(true)
                .build());

        if (!response.hasItem() || !response.item().containsKey("taskType")) {
            return "ANALYZE";
        }
        return response.item().get("taskType").s();
    }

    public String getResultJson(String jobId) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("jobId", s(jobId)))
                .projectionExpression("resultJson")
                .build());

        if (!response.hasItem() || !response.item().containsKey("resultJson")) {
            return null;
        }
        return response.item().get("resultJson").s();
    }

    /**
     * Streaming-preview write. The INSIGHTS worker calls this every ~500ms or
     * ~50 tokens while Gemini streams output, so the frontend's polling loop
     * sees a growing snapshot of the narrative as it's generated.
     *
     * <p>Conditional on {@code status = PROCESSING} so a delayed write cannot
     * clobber a row that has already transitioned to COMPLETED / FAILED. The
     * partial text is just a UX preview — terminal state (resultJson / errorMessage)
     * always wins. If the condition fails we swallow at WARN level: the race
     * is benign because the terminal write carries the source-of-truth payload.
     *
     * <p>Note: this method does NOT throw on infrastructure errors either —
     * partialText is best-effort. A DDB outage that fails this write must not
     * propagate up and kill the user-facing Gemini call.
     */
    public void updatePartialText(String jobId, String partialText) {
        try {
            dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("jobId", s(jobId)))
                    .updateExpression("SET partialText = :p")
                    .conditionExpression("#st = :processing")
                    .expressionAttributeNames(Map.of("#st", "status"))
                    .expressionAttributeValues(Map.of(
                            ":p", s(partialText),
                            ":processing", s("PROCESSING")
                    ))
                    .build());
        } catch (software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException e) {
            // Row has transitioned to a terminal state since we read it.
            // Terminal state takes precedence over the preview — drop the write.
            LOG.debugf("partialText write skipped for job %s — row no longer PROCESSING", jobId);
        } catch (RuntimeException e) {
            // DDB outage / throttling / serialization — log and continue. partialText
            // is a UX preview, not the source of truth. The terminal updateCompleted /
            // updateFailed will still land via the worker's normal flow.
            LOG.warnf(e, "partialText write failed for job %s (swallowed — preview only)", jobId);
        }
    }

    /**
     * Overwrite the resultJson of a COMPLETED job — used when the manager edits
     * the AI Narrative draft and saves their version back. Conditional on
     * {@code status = COMPLETED} so a PROCESSING or FAILED row can't be poisoned
     * by a stray edit. Throws {@link software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException}
     * if the row isn't COMPLETED (caller maps this to 409).
     *
     * <p>Stamps {@code editedAt} (ISO-8601 UTC) so we can tell AI-original from
     * manager-edited rows at calibration time. Telemetry fields (tokenIn / tokenOut /
     * modelUsed / promptVersion) are NOT touched — they reflect the Gemini call cost,
     * which remains accurate even after a manual edit.
     */
    public void updateResultJson(String jobId, String newResultJson) {
        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("jobId", s(jobId)))
                .updateExpression("SET resultJson = :r, editedAt = :e")
                .conditionExpression("attribute_exists(jobId) AND #st = :completed")
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(Map.of(
                        ":r", s(newResultJson),
                        ":e", s(java.time.Instant.now().toString()),
                        ":completed", s("COMPLETED")
                ))
                .build());
    }

    /**
     * Clears any in-flight partialText. Called from the worker's FAILED-transition
     * path so the frontend's existing failed-state banner renders cleanly without
     * a half-streamed narrative ghosting underneath it.
     *
     * <p>Same best-effort contract as {@link #updatePartialText} — swallows
     * errors. If clearing fails, the worst case is a stale preview lingering for
     * up to a few seconds before the frontend's next poll picks up status=FAILED
     * and stops rendering the preview anyway.
     */
    public void clearPartialText(String jobId) {
        try {
            dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("jobId", s(jobId)))
                    .updateExpression("REMOVE partialText")
                    .build());
        } catch (RuntimeException e) {
            LOG.warnf(e, "clearPartialText failed for job %s (swallowed — preview only)", jobId);
        }
    }

    /**
     * Read the streaming preview snapshot for a polling caller.
     * Returns null if the field is absent (job never streamed, or stream
     * was cleared after FAILED).
     */
    public String getPartialText(String jobId) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("jobId", s(jobId)))
                .projectionExpression("partialText")
                .build());

        if (!response.hasItem() || !response.item().containsKey("partialText")) {
            return null;
        }
        return response.item().get("partialText").s();
    }
}

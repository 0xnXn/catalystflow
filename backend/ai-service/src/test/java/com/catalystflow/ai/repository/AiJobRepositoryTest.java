package com.catalystflow.ai.repository;

import com.catalystflow.ai.model.AiReviewJobStatus;
import com.catalystflow.ai.model.AiReviewStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiJobRepositoryTest {

    private AiJobRepository repo;
    private DynamoDbClient dynamo;

    @BeforeEach
    void setUp() throws Exception {
        repo = new AiJobRepository();
        dynamo = mock(DynamoDbClient.class);
        setField(repo, "dynamoDb", dynamo);
        setField(repo, "tableName", "AiJobsTest");
        setField(repo, "completedTtlDays", 365L);
    }

    @Test
    void create_withTaskType_writesExpectedItem() {
        repo.create("job-1", "INSIGHTS", "{\"k\":1}", 123L);
        verify(dynamo).putItem(any(PutItemRequest.class));
    }

    @Test
    void create_defaultOverload_usesAnalyzeTask() {
        repo.create("job-1a", "{\"k\":2}", 456L);
        verify(dynamo).putItem(any(PutItemRequest.class));
    }

    @Test
    void createForDigest_stampsDigestKeySubjectCycleAndCreatedAt() {
        repo.createForDigest("djob-1", "PEER_360_DIGEST", "{}", 999L, "subj-1", "cyc-1", 1000L);
        ArgumentCaptor<PutItemRequest> cap = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(cap.capture());
        Map<String, AttributeValue> item = cap.getValue().item();
        assertEquals("subj-1#cyc-1", item.get("digestKey").s());   // sparse GSI hash key
        assertEquals("subj-1", item.get("subjectUserId").s());
        assertEquals("cyc-1", item.get("cycleId").s());
        assertEquals("1000", item.get("createdAt").n());           // GSI sort key
        assertEquals("PENDING", item.get("status").s());
        assertEquals("PEER_360_DIGEST", item.get("taskType").s());
    }

    @Test
    void latestCompletedDigest_returnsNewestCompletedJobId() {
        Map<String, AttributeValue> newest = Map.of(
                "jobId", AttributeValue.builder().s("digest-new").build(),
                "status", AttributeValue.builder().s("COMPLETED").build());
        when(dynamo.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of(newest)).build());
        assertEquals("digest-new", repo.latestCompletedDigest("subj-1", "cyc-1"));
    }

    @Test
    void latestCompletedDigest_noItems_returnsNull() {
        when(dynamo.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).build());
        assertNull(repo.latestCompletedDigest("subj-1", "cyc-1"));
    }

    @Test
    void tryClaim_success_returnsTrue() {
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenReturn(null);
        assertTrue(repo.tryClaim("job-2"));
    }

    @Test
    void tryClaim_conditionalFailure_returnsFalse() {
        when(dynamo.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("claimed").build());
        assertFalse(repo.tryClaim("job-3"));
    }

    @Test
    void get_withItem_returnsMappedStatus() {
        Map<String, AttributeValue> item = Map.of(
                "status", AttributeValue.builder().s("FAILED").build(),
                "errorMessage", AttributeValue.builder().s("boom").build()
        );
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(item).build());

        AiReviewStatusResponse status = repo.get("job-4");
        assertNotNull(status);
        assertEquals("job-4", status.getJobId());
        assertEquals(AiReviewJobStatus.FAILED, status.getStatus());
        assertEquals("boom", status.getErrorMessage());
    }

    @Test
    void get_missingItem_returnsNull() {
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());
        assertNull(repo.get("job-5"));
    }

    @Test
    void getRequestTaskResultJson_defaultBranches() {
        when(dynamo.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());
        assertNull(repo.getRequestJson("job-6"));
        assertEquals("ANALYZE", repo.getTaskType("job-6"));
        assertNull(repo.getResultJson("job-6"));
    }

    @Test
    void updateMethods_invokeDynamo() {
        repo.updateProcessing("job-7");
        repo.updateCompleted("job-7", "{}");
        repo.updateFailed("job-7", "err");
        verify(dynamo, times(3)).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void updateCompleted_extendsTtl_toConfiguredRetention() {
        // A COMPLETED job must outlive the short ~7-day creation TTL so a finalized
        // review's AI survives later revisits without re-firing Gemini.
        long before = java.time.Instant.now().getEpochSecond();
        repo.updateCompleted("job-ttl", "{\"ok\":1}");

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(captor.capture());
        UpdateItemRequest req = captor.getValue();

        assertTrue(req.updateExpression().contains("expiresAt = :exp"),
                "COMPLETED update must extend the TTL: " + req.updateExpression());
        AttributeValue exp = req.expressionAttributeValues().get(":exp");
        assertNotNull(exp, ":exp must be bound");
        long expSec = Long.parseLong(exp.n());
        long expectedMin = before + 365L * 86_400L;
        assertTrue(expSec >= expectedMin && expSec <= expectedMin + 5,
                "expiresAt must be ~now + 365d; got " + expSec + ", expected ~" + expectedMin);
    }

    @Test
    void updateCompleted_nonPositiveRetention_doesNotTouchTtl() throws Exception {
        // Footgun guard: a misconfigured 0/negative retention must NOT set
        // expiresAt=now (which would delete completed jobs immediately) — it
        // leaves the creation TTL untouched.
        setField(repo, "completedTtlDays", 0L);
        repo.updateCompleted("job-ttl0", "{}");

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(captor.capture());
        assertFalse(captor.getValue().updateExpression().contains("expiresAt"),
                "non-positive retention must not write expiresAt: " + captor.getValue().updateExpression());
    }

    @Test
    void updateFailed_nullMessage_usesUnknownErrorFallback() {
        repo.updateFailed("job-8", null);
        verify(dynamo).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void updateFailed_withRepairRetries_persistsAsAttribute() {
        // RS-2 follow-up: the 7-arg overload was added so SchemaInvalidException
        // can persist repairRetries=0 on FAILED rows. Pre-fix the column was
        // always absent, making the "post-RS-2 repairRetries=1 means the loop
        // regressed" alarm signal impossible to detect.
        repo.updateFailed("job-schema", "SCHEMA_INVALID: missing field",
                250, 80, "gemini-test", "MANAGER_GOLDEN_V1", 0);

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(captor.capture());
        UpdateItemRequest req = captor.getValue();

        assertTrue(req.updateExpression().contains("repairRetries"),
                "Update expression must SET repairRetries when caller supplies it: "
                        + req.updateExpression());
        AttributeValue rr = req.expressionAttributeValues().get(":repairRetries");
        assertNotNull(rr, "repairRetries expression attribute must be bound");
        assertEquals("0", rr.n(), "repairRetries=0 must persist as a numeric attribute");
    }

    @Test
    void updateFailed_nullRepairRetries_omitsAttribute() {
        // Null-omission rule: callers without repair semantics (timeout / rate-
        // limit / generic failure) leave the attribute absent rather than
        // writing DDB NULL. Downstream queries distinguish "telemetry not
        // captured" (absent) from "captured, value was 0" (N=0).
        repo.updateFailed("job-timeout", "TIMEOUT: deadline",
                100, 0, "gemini-test", "ANALYZE_V1", null);

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(captor.capture());
        UpdateItemRequest req = captor.getValue();

        assertFalse(req.updateExpression().contains("repairRetries"),
                "Update expression must NOT SET repairRetries when caller passes null: "
                        + req.updateExpression());
    }

    @Test
    void updateFailed_sixArgOverload_delegatesWithNullRepairRetries() {
        // Backward-compatibility check — existing callers using the 6-arg form
        // must continue to produce attribute-absent rows (no DDB NULL, no zero).
        repo.updateFailed("job-legacy", "UPSTREAM_5XX (503)",
                200, 50, "gemini-test", "INSIGHTS_V1");

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(captor.capture());
        UpdateItemRequest req = captor.getValue();

        assertFalse(req.updateExpression().contains("repairRetries"),
                "6-arg overload must not write repairRetries: " + req.updateExpression());
    }

    // ── INSIGHTS streaming preview (partialText) ──────────────────────────────

    @Test
    void updatePartialText_writesConditionalUpdate_whenStatusProcessing() {
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenReturn(null);

        repo.updatePartialText("job-stream-1", "Aarti demonstrated…");

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo, times(1)).updateItem(captor.capture());
        UpdateItemRequest req = captor.getValue();

        assertTrue(req.updateExpression().contains("SET partialText = :p"),
                "expected SET partialText = :p, got: " + req.updateExpression());
        assertEquals("#st = :processing", req.conditionExpression(),
                "must guard the write on status=PROCESSING to avoid clobbering terminal state");
        assertEquals("PROCESSING", req.expressionAttributeValues().get(":processing").s());
        assertEquals("Aarti demonstrated…", req.expressionAttributeValues().get(":p").s());
        assertEquals("status", req.expressionAttributeNames().get("#st"));
    }

    @Test
    void updatePartialText_swallowsConditionalCheckFailure_whenRowAlreadyCompleted() {
        // Simulates the race where a flush arrives just after status transitioned
        // to COMPLETED. Terminal state wins; preview write is dropped silently.
        when(dynamo.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("not processing").build());

        // Must not throw — partialText is best-effort.
        repo.updatePartialText("job-stream-2", "should-be-dropped");

        verify(dynamo, times(1)).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void updatePartialText_swallowsRuntimeException_protectsCaller() {
        // DDB outage / throttling — partialText write must not propagate up
        // and kill the user-facing Gemini call.
        when(dynamo.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(new RuntimeException("simulated DDB outage"));

        // Must not throw.
        repo.updatePartialText("job-stream-3", "any-text");
    }

    @Test
    void clearPartialText_removesField_unconditional() {
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenReturn(null);

        repo.clearPartialText("job-stream-4");

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo, times(1)).updateItem(captor.capture());
        UpdateItemRequest req = captor.getValue();

        assertEquals("REMOVE partialText", req.updateExpression());
        // No condition — terminal-state writes have already landed by the time
        // we clear; the REMOVE is idempotent on a missing field anyway.
        assertNull(req.conditionExpression(),
                "clearPartialText must not gate on a condition — the FAILED transition has already won");
    }

    @Test
    void clearPartialText_swallowsRuntimeException() {
        when(dynamo.updateItem(any(UpdateItemRequest.class)))
                .thenThrow(new RuntimeException("simulated DDB outage"));

        // Must not throw — failure to clear leaves a stale preview that the next
        // poll's status=FAILED will hide; not a user-visible defect.
        repo.clearPartialText("job-stream-5");
    }

    @Test
    void getPartialText_returnsField_whenPresent() {
        Map<String, AttributeValue> item = Map.of(
                "partialText", AttributeValue.builder().s("streaming preview text").build()
        );
        when(dynamo.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());

        assertEquals("streaming preview text", repo.getPartialText("job-stream-6"));
    }

    @Test
    void getPartialText_returnsNull_whenFieldAbsent() {
        Map<String, AttributeValue> item = Map.of(
                "status", AttributeValue.builder().s("PROCESSING").build()
        );
        when(dynamo.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());

        assertNull(repo.getPartialText("job-stream-7"));
    }

    @Test
    void getPartialText_returnsNull_whenItemAbsent() {
        when(dynamo.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        assertNull(repo.getPartialText("job-stream-8"));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}

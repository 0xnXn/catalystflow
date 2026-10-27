package com.catalystflow.ai.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CC-1 / CC-2 — pure unit tests for the DDB I/O layer.
 *
 * <p>Mirrors {@link AiJobRepositoryTest}: instantiate the repo, mock
 * {@link DynamoDbClient}, set package-private fields by reflection. No
 * Quarkus bootstrap.
 */
class GeminiContextCacheRepositoryTest {

    private GeminiContextCacheRepository repo;
    private DynamoDbClient dynamo;

    @BeforeEach
    void setUp() throws Exception {
        repo = new GeminiContextCacheRepository();
        dynamo = mock(DynamoDbClient.class);
        setField(repo, "dynamoDb", dynamo);
        setField(repo, "tableName", "GeminiCacheTest");
    }

    // ── get ──────────────────────────────────────────────────────────────────

    @Test
    void get_missingRow_returnsEmpty() {
        when(dynamo.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        Optional<GeminiContextCacheRepository.CacheEntry> result = repo.get("nope");

        assertTrue(result.isEmpty());
    }

    @Test
    void get_existingRow_parsesAllFields() {
        Map<String, AttributeValue> item = Map.of(
                "cacheKey", s("k1"),
                "cacheName", s("cachedContents/abc"),
                "taskType", s("ANALYZE"),
                "promptVersion", s("ANALYZE_V2"),
                "contentHash", s("0123456789abcdef"),
                "createdAt", n(1_700_000_000L),
                "expiresAt", n(1_700_003_600L),
                "cachedTokens", n(2000L),
                "entryType", s(GeminiContextCacheRepository.ENTRY_TYPE_CACHE));
        when(dynamo.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());

        Optional<GeminiContextCacheRepository.CacheEntry> result = repo.get("k1");

        assertTrue(result.isPresent());
        GeminiContextCacheRepository.CacheEntry e = result.get();
        assertEquals("k1", e.cacheKey());
        assertEquals("cachedContents/abc", e.cacheName());
        assertEquals("ANALYZE", e.taskType());
        assertEquals("ANALYZE_V2", e.promptVersion());
        assertEquals("0123456789abcdef", e.contentHash());
        assertEquals(1_700_000_000L, e.createdAt());
        assertEquals(1_700_003_600L, e.expiresAt());
        assertEquals(Integer.valueOf(2000), e.cachedTokens());
        assertEquals(GeminiContextCacheRepository.ENTRY_TYPE_CACHE, e.entryType());
    }

    @Test
    void get_existingTombstoneRow_parsesEntryTypeUnderThreshold() {
        // CC-4b follow-up MEDIUM: the discriminator must survive the round
        // trip. Tombstone rows persist entryType=UNDER_THRESHOLD; the parse
        // step must read that back so GeminiCacheService can short-circuit
        // without inferring from the cacheName sentinel.
        Map<String, AttributeValue> item = Map.of(
                "cacheKey", s("ktomb"),
                "cacheName", s("[under-threshold]"),
                "taskType", s("MANAGER_GOLDEN"),
                "promptVersion", s("MANAGER_GOLDEN_V2"),
                "contentHash", s("aabbccddeeff0011"),
                "createdAt", n(1L),
                "expiresAt", n(601L),
                "cachedTokens", n(700L),
                "entryType", s(GeminiContextCacheRepository.ENTRY_TYPE_UNDER_THRESHOLD));
        when(dynamo.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());

        GeminiContextCacheRepository.CacheEntry e = repo.get("ktomb").orElseThrow();

        assertEquals(GeminiContextCacheRepository.ENTRY_TYPE_UNDER_THRESHOLD,
                e.entryType(),
                "Tombstone entryType must round-trip — sentinel cacheName is informational, "
                        + "entryType is the source of truth for the row type discriminator");
    }

    @Test
    void get_legacyRowWithoutEntryType_defaultsToCacheType() {
        // CC-4b follow-up MEDIUM forward-compat: rows written before the
        // entryType column existed and whose cacheName is NOT the tombstone
        // sentinel must default to CACHE — those were real positive cache
        // rows written by the original CC-2 implementation. Legacy rows
        // expire naturally via DDB TTL; this tolerance is needed only for
        // their lifetime.
        Map<String, AttributeValue> item = Map.of(
                "cacheKey", s("klegacy"),
                "cacheName", s("cachedContents/legacy"),
                "taskType", s("ANALYZE"),
                "promptVersion", s("ANALYZE_V2"),
                "contentHash", s("0011223344556677"),
                "createdAt", n(1L),
                "expiresAt", n(3601L));
        when(dynamo.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());

        GeminiContextCacheRepository.CacheEntry e = repo.get("klegacy").orElseThrow();

        assertEquals(GeminiContextCacheRepository.ENTRY_TYPE_CACHE, e.entryType(),
                "Legacy rows without entryType but with a real cachedContents/<id> "
                        + "cacheName must default to CACHE");
    }

    @Test
    void get_legacyTombstoneWithoutEntryType_inferredFromSentinelCacheName() {
        // Round-3 follow-up: commit e66e0a5 introduced the tombstone path
        // before the entryType discriminator existed. Tombstones from that
        // brief window have cacheName="[under-threshold]" AND no entryType
        // column. The naive "default missing entryType to CACHE" rule
        // (pre-fix) misclassified them as real cache rows, so
        // GeminiCacheService.resolveOrCreate would return "[under-threshold]"
        // as a cacheName and GeminiClient would send
        // `cachedContent: "[under-threshold]"` to Gemini → upstream 4xx →
        // user-facing 502 on every legacy-tombstone hit until the row's TTL
        // expired.
        //
        // Fix: parse() back-infers entryType from cacheName when entryType is
        // absent. Sentinel cacheName → UNDER_THRESHOLD. Real cacheName → CACHE.
        // This test pins that contract so any future parser edit that
        // re-introduces the naive default fails loudly.
        Map<String, AttributeValue> item = Map.of(
                "cacheKey", s("klegacytomb"),
                "cacheName", s(GeminiContextCacheRepository.UNDER_THRESHOLD_SENTINEL),
                "taskType", s("MANAGER_GOLDEN"),
                "promptVersion", s("MANAGER_GOLDEN_V2"),
                "contentHash", s("aabbccddeeff0011"),
                "createdAt", n(1L),
                "expiresAt", n(601L),
                "cachedTokens", n(700L));
        when(dynamo.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());

        GeminiContextCacheRepository.CacheEntry e = repo.get("klegacytomb").orElseThrow();

        assertEquals(GeminiContextCacheRepository.ENTRY_TYPE_UNDER_THRESHOLD,
                e.entryType(),
                "Legacy tombstone (sentinel cacheName, no entryType) MUST back-infer "
                        + "UNDER_THRESHOLD — otherwise GeminiCacheService.resolveOrCreate "
                        + "returns the sentinel as a cacheName and GeminiClient sends "
                        + "cachedContent: '[under-threshold]' to Gemini, getting 4xx → "
                        + "user-facing 502 on every legacy-tombstone hit.");
    }

    @Test
    void get_rowWithoutCachedTokens_returnsNullCachedTokens() {
        // cachedTokens is nullable per the model — Gemini doesn't always
        // emit it on cache create. Verify the parser handles its absence.
        Map<String, AttributeValue> item = Map.of(
                "cacheKey", s("k2"),
                "cacheName", s("cachedContents/def"),
                "taskType", s("MANAGER_GOLDEN"),
                "promptVersion", s("MANAGER_GOLDEN_V2"),
                "contentHash", s("fedcba9876543210"),
                "createdAt", n(1L),
                "expiresAt", n(601L));
        when(dynamo.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(item).build());

        GeminiContextCacheRepository.CacheEntry e = repo.get("k2").orElseThrow();

        assertNull(e.cachedTokens());
    }

    @Test
    void get_usesConsistentRead() {
        // Cache lookups happen synchronously in the per-request critical
        // path; eventually-consistent reads could return a stale "not found"
        // moments after a parallel cache write completed. Pin consistentRead=true.
        when(dynamo.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        repo.get("k3");

        ArgumentCaptor<GetItemRequest> cap = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamo).getItem(cap.capture());
        assertTrue(cap.getValue().consistentRead());
    }

    // ── put ──────────────────────────────────────────────────────────────────

    @Test
    void put_writesAllFields_includingCachedTokens_andEntryType() {
        repo.put("k4", "cachedContents/xyz",
                "INSIGHTS", "INSIGHTS_V2",
                "deadbeefdeadbeef", 100L, 1900L, 1500,
                GeminiContextCacheRepository.ENTRY_TYPE_CACHE);

        ArgumentCaptor<PutItemRequest> cap = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(cap.capture());
        Map<String, AttributeValue> item = cap.getValue().item();

        assertEquals("k4", item.get("cacheKey").s());
        assertEquals("cachedContents/xyz", item.get("cacheName").s());
        assertEquals("INSIGHTS", item.get("taskType").s());
        assertEquals("INSIGHTS_V2", item.get("promptVersion").s());
        assertEquals("deadbeefdeadbeef", item.get("contentHash").s());
        assertEquals("100", item.get("createdAt").n());
        assertEquals("1900", item.get("expiresAt").n());
        assertEquals("1500", item.get("cachedTokens").n());
        assertEquals(GeminiContextCacheRepository.ENTRY_TYPE_CACHE,
                item.get("entryType").s(),
                "entryType must be persisted — admin DELETE (CC-6) uses it as the "
                        + "discriminator for whether to call Gemini DELETE");
    }

    @Test
    void put_tombstone_persistsEntryTypeUnderThreshold() {
        // CC-4b follow-up MEDIUM: tombstones go through the same put method
        // with the sentinel cacheName + UNDER_THRESHOLD entryType. Pin both
        // attributes so an admin scan or CC-6 DELETE caller can identify
        // tombstones unambiguously.
        repo.put("ktomb", "[under-threshold]",
                "MANAGER_GOLDEN", "MANAGER_GOLDEN_V2",
                "1122334455667788", 100L, 700L, 650,
                GeminiContextCacheRepository.ENTRY_TYPE_UNDER_THRESHOLD);

        ArgumentCaptor<PutItemRequest> cap = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(cap.capture());
        Map<String, AttributeValue> item = cap.getValue().item();

        assertEquals("[under-threshold]", item.get("cacheName").s(),
                "Sentinel cacheName persists for forensic visibility");
        assertEquals(GeminiContextCacheRepository.ENTRY_TYPE_UNDER_THRESHOLD,
                item.get("entryType").s(),
                "entryType=UNDER_THRESHOLD is the source-of-truth discriminator "
                        + "— admin DELETE must skip Gemini DELETE for tombstones");
    }

    @Test
    void put_omitsCachedTokensWhenNull() {
        // Null cachedTokens must NOT be persisted (no DDB NULL attribute) —
        // downstream queries can then distinguish "not captured" from
        // "captured value was 0". Same null-omission rule as
        // AiJobRepository.updateCompleted.
        repo.put("k5", "cachedContents/none",
                "ANALYZE", "ANALYZE_V2",
                "0011223344556677", 50L, 3650L, null,
                GeminiContextCacheRepository.ENTRY_TYPE_CACHE);

        ArgumentCaptor<PutItemRequest> cap = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamo).putItem(cap.capture());
        assertFalse(cap.getValue().item().containsKey("cachedTokens"),
                "cachedTokens=null must be OMITTED from the put item, not stored as DDB NULL");
    }

    // ── listAll (CC-6 admin) ─────────────────────────────────────────────────

    @Test
    void listAll_paginatesViaLastEvaluatedKey() {
        // CC-6 admin: scan loops on LastEvaluatedKey so all rows are
        // returned, not just the first page. Two pages
        // here: first scan returns a continuation key, second scan returns no
        // key (end of results).
        Map<String, AttributeValue> page1Item1 = Map.of(
                "cacheKey", s("k-p1-1"), "cacheName", s("cachedContents/one"),
                "taskType", s("ANALYZE"), "promptVersion", s("ANALYZE_V2"),
                "contentHash", s("h1"), "createdAt", n(1L), "expiresAt", n(2L),
                "entryType", s(GeminiContextCacheRepository.ENTRY_TYPE_CACHE));
        Map<String, AttributeValue> page2Item1 = Map.of(
                "cacheKey", s("k-p2-1"), "cacheName", s("[under-threshold]"),
                "taskType", s("MANAGER_GOLDEN"), "promptVersion", s("MANAGER_GOLDEN_V2"),
                "contentHash", s("h2"), "createdAt", n(3L), "expiresAt", n(4L),
                "entryType", s(GeminiContextCacheRepository.ENTRY_TYPE_UNDER_THRESHOLD));
        Map<String, AttributeValue> lastEvalKey = Map.of("cacheKey", s("k-p1-1"));

        when(dynamo.scan(any(software.amazon.awssdk.services.dynamodb.model.ScanRequest.class)))
                .thenReturn(
                        software.amazon.awssdk.services.dynamodb.model.ScanResponse.builder()
                                .items(java.util.List.of(page1Item1))
                                .lastEvaluatedKey(lastEvalKey)
                                .build(),
                        software.amazon.awssdk.services.dynamodb.model.ScanResponse.builder()
                                .items(java.util.List.of(page2Item1))
                                .build());

        java.util.List<GeminiContextCacheRepository.CacheEntry> all = repo.listAll();

        assertEquals(2, all.size(),
                "Both pages must be aggregated into a single result list");
        assertEquals("k-p1-1", all.get(0).cacheKey());
        assertEquals("k-p2-1", all.get(1).cacheKey());
        assertEquals(GeminiContextCacheRepository.ENTRY_TYPE_UNDER_THRESHOLD,
                all.get(1).entryType(),
                "Tombstone row's entryType must round-trip through pagination");
        // Two scans fired: one with no ExclusiveStartKey, one with the key.
        verify(dynamo, org.mockito.Mockito.times(2))
                .scan(any(software.amazon.awssdk.services.dynamodb.model.ScanRequest.class));
    }

    @Test
    void listAll_emptyTable_returnsEmptyList() {
        when(dynamo.scan(any(software.amazon.awssdk.services.dynamodb.model.ScanRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.ScanResponse.builder()
                        .items(java.util.List.of())
                        .build());

        assertTrue(repo.listAll().isEmpty(),
                "Empty table → empty list, no infinite loop on missing LastEvaluatedKey");
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    void delete_keysByCacheKey() {
        repo.delete("k6");

        ArgumentCaptor<DeleteItemRequest> cap = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(dynamo).deleteItem(cap.capture());
        assertEquals("k6", cap.getValue().key().get("cacheKey").s());
        assertEquals("GeminiCacheTest", cap.getValue().tableName());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static AttributeValue s(String v) {
        return AttributeValue.builder().s(v).build();
    }

    private static AttributeValue n(long v) {
        return AttributeValue.builder().n(String.valueOf(v)).build();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}

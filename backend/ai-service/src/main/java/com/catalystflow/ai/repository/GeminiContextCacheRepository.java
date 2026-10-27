package com.catalystflow.ai.repository;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DDB I/O for the {@code CatalystFlow_GeminiContextCache} table (CC-1).
 *
 * <p>Maps a content-hashed {@code cacheKey} to a Gemini
 * {@code cachedContents/<id>} resource name plus the metadata needed to
 * reason about whether the cache row is still usable
 * ({@code expiresAt}) and to attribute reuse savings ({@code cachedTokens}).
 *
 * <p>This class is the I/O layer only. {@link com.catalystflow.ai.service.GeminiCacheService}
 * is the orchestrator that calls {@link #get(String)} → maybe
 * {@code GeminiClient.callCachedContents} → {@link #put} on miss.
 * Separating them mirrors the existing
 * {@link com.catalystflow.ai.repository.AiJobRepository} pattern and keeps
 * the service layer free of DDB SDK types.
 */
@ApplicationScoped
public class GeminiContextCacheRepository {

    private static final Logger LOG = Logger.getLogger(GeminiContextCacheRepository.class);

    @Inject
    DynamoDbClient dynamoDb;

    @ConfigProperty(name = "gemini-cache.table-name")
    String tableName;

    /**
     * Explicit row-type discriminator. Stored as DDB column {@code entryType}
     * (String). The {@code cacheName} column is "the Gemini cache resource
     * name" iff {@code entryType == ENTRY_TYPE_CACHE}; for other entry types
     * {@code cacheName} is informational / sentinel-only and must NOT be sent
     * to Gemini.
     *
     * <p>Why a discriminator (CC-4b follow-up MEDIUM): we previously overloaded
     * {@code cacheName} with a sentinel string ({@code "[under-threshold]"})
     * to mark negative-cache (tombstone) rows. That made
     * {@link com.catalystflow.ai.service.GeminiCacheService#resolveOrCreate}
     * work but would have tripped CC-6's planned admin DELETE path: deleting
     * a tombstone via Gemini {@code DELETE /v1beta/{cacheName}} would error
     * because no upstream resource exists. The explicit field makes the
     * "is this a real Gemini cacheName" question a one-field check at every
     * admin / observability site.
     *
     * <h2>Constants</h2>
     * <ul>
     *   <li>{@link #ENTRY_TYPE_CACHE} — {@code cacheName} is a real
     *       {@code cachedContents/<id>} resource. Admin DELETE must call
     *       Gemini.</li>
     *   <li>{@link #ENTRY_TYPE_UNDER_THRESHOLD} — tombstone for a cacheKey
     *       whose content size is below
     *       {@link com.catalystflow.ai.service.GeminiCacheService#MIN_CACHEABLE_TOKENS}.
     *       {@code cacheName} is the sentinel
     *       ({@code "[under-threshold]"}) for forensic visibility in DDB
     *       inspectors; admin DELETE must skip the Gemini call.</li>
     * </ul>
     *
     * <h2>Legacy-row tolerance</h2>
     * Older rows written before CC-4b follow-up have no {@code entryType}
     * attribute on DDB. {@link #parse} defaults missing values to
     * {@link #ENTRY_TYPE_CACHE} — those rows were all real Gemini caches
     * (the tombstone path didn't exist), so the default preserves behaviour.
     */
    public static final String ENTRY_TYPE_CACHE = "CACHE";
    public static final String ENTRY_TYPE_UNDER_THRESHOLD = "UNDER_THRESHOLD";

    /**
     * Sentinel {@code cacheName} written into negative-cache (tombstone) rows
     * for forensic visibility in DDB inspectors. Logic everywhere keys on
     * {@link #ENTRY_TYPE_UNDER_THRESHOLD} (the discriminator), NOT this
     * string — but the value is also referenced by {@link #parse} to
     * back-infer the entryType for rows written before the entryType column
     * existed (see the parse() comment).
     *
     * <p>The constant lives on the repository (not the service) because it
     * is persisted data — the storage layer is its natural home, and it lets
     * {@link #parse} reference it without a layer-violating import.
     */
    public static final String UNDER_THRESHOLD_SENTINEL = "[under-threshold]";

    /**
     * One row of the cache index, surfaced to {@link com.catalystflow.ai.service.GeminiCacheService}
     * for hit / miss / expired decisions. Attribute set mirrors AC-7.1's
     * column list (cacheName, taskType, promptVersion, contentHash,
     * createdAt, expiresAt, cachedTokens) plus the CC-4b-follow-up
     * {@code entryType} discriminator. {@code cachedTokens} is nullable —
     * Gemini doesn't always return it on cache creation and it's only used
     * by CC-5's hit-rate dashboard.
     */
    @RegisterForReflection
    public record CacheEntry(
            String cacheKey,
            String cacheName,
            String taskType,
            String promptVersion,
            String contentHash,
            long createdAt,
            long expiresAt,
            Integer cachedTokens,
            String entryType) {
    }

    /**
     * Look up a cache row by its full cacheKey. Returns {@link Optional#empty}
     * for a true miss (no item) — the service treats expiry-soon as a miss
     * too via its own clock check, not here.
     */
    public Optional<CacheEntry> get(String cacheKey) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("cacheKey", s(cacheKey)))
                .consistentRead(true)
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(parse(response.item()));
    }

    /**
     * Write a freshly-created cache binding OR a negative-cache tombstone.
     * Overwrites any existing row at the same cacheKey — that's the
     * intentional "refresh after TTL" path. {@code cachedTokens} may be null;
     * it's persisted as absent rather than DDB NULL so a downstream query
     * can tell "not captured" from "captured value was 0".
     *
     * @param entryType {@link #ENTRY_TYPE_CACHE} for real Gemini cache rows;
     *                  {@link #ENTRY_TYPE_UNDER_THRESHOLD} for tombstones.
     *                  Always persisted as a DDB attribute so the row's type
     *                  is unambiguous at read time (no inference from
     *                  cacheName).
     */
    public void put(String cacheKey,
                    String cacheName,
                    String taskType,
                    String promptVersion,
                    String contentHash,
                    long createdAt,
                    long expiresAt,
                    Integer cachedTokens,
                    String entryType) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("cacheKey", s(cacheKey));
        item.put("cacheName", s(cacheName));
        item.put("taskType", s(taskType));
        item.put("promptVersion", s(promptVersion));
        item.put("contentHash", s(contentHash));
        item.put("createdAt", n(createdAt));
        item.put("expiresAt", n(expiresAt));
        item.put("entryType", s(entryType));
        if (cachedTokens != null) {
            item.put("cachedTokens", n(cachedTokens));
        }
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());
    }

    /**
     * CC-6 admin invalidation path. Hard-delete a single cache row so the
     * next resolveOrCreate forces a fresh Gemini cache create. Best-effort;
     * Gemini's own cachedContents resource is deleted via a separate
     * GeminiClient call by the CC-6 admin endpoint.
     */
    public void delete(String cacheKey) {
        dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("cacheKey", s(cacheKey)))
                .build());
    }

    /**
     * CC-6 admin inventory path. Returns every row in the cache table — both
     * real CACHE entries and UNDER_THRESHOLD tombstones. Paginates internally
     * by looping on {@code LastEvaluatedKey} so all rows are returned, not
     * just the first page.
     *
     * <p>Acceptable for the admin endpoint because the cache table size is
     * bounded — at most O(disciplines × seniorities × prompt versions × task
     * types) entries, with DDB TTL trimming expired rows. Real-world cap is
     * a few hundred rows. If that ever grows past a few thousand, paginate
     * the response itself (CC-6.2 currently returns the full set).
     */
    public java.util.List<CacheEntry> listAll() {
        java.util.List<CacheEntry> all = new java.util.ArrayList<>();
        Map<String, AttributeValue> lastEvaluatedKey = null;
        do {
            software.amazon.awssdk.services.dynamodb.model.ScanRequest.Builder reqBuilder =
                    software.amazon.awssdk.services.dynamodb.model.ScanRequest.builder()
                            .tableName(tableName);
            if (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty()) {
                reqBuilder.exclusiveStartKey(lastEvaluatedKey);
            }
            software.amazon.awssdk.services.dynamodb.model.ScanResponse response =
                    dynamoDb.scan(reqBuilder.build());
            for (Map<String, AttributeValue> item : response.items()) {
                all.add(parse(item));
            }
            lastEvaluatedKey = response.hasLastEvaluatedKey() ? response.lastEvaluatedKey() : null;
        } while (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty());
        return all;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static CacheEntry parse(Map<String, AttributeValue> item) {
        // CC-4b follow-up round-3: entryType is a new attribute. Rows written
        // before this change have no entryType column. The naive default —
        // "assume CACHE for everything legacy" — is WRONG for tombstone rows
        // written by the brief window of commit e66e0a5 (the negative-cache
        // implementation before the entryType discriminator landed). Those
        // rows have cacheName="[under-threshold]" and no entryType, and
        // defaulting them to CACHE would make resolveOrCreate return
        // "[under-threshold]" as a real cacheName — GeminiClient would then
        // send `cachedContent: "[under-threshold]"` to Gemini and get a 4xx,
        // turning every cache lookup against a legacy tombstone into a
        // user-facing 502.
        //
        // Back-inference: when entryType is absent, look at cacheName. If it
        // matches the sentinel, the row is a legacy tombstone — default to
        // UNDER_THRESHOLD. Otherwise it's a legacy positive cache row —
        // default to CACHE. Legacy rows expire naturally via DDB TTL; this
        // migration tolerance is needed only for the lifetime of those rows.
        String entryType = str(item, "entryType");
        if (entryType == null) {
            String cacheName = str(item, "cacheName");
            entryType = UNDER_THRESHOLD_SENTINEL.equals(cacheName)
                    ? ENTRY_TYPE_UNDER_THRESHOLD
                    : ENTRY_TYPE_CACHE;
        }
        return new CacheEntry(
                str(item, "cacheKey"),
                str(item, "cacheName"),
                str(item, "taskType"),
                str(item, "promptVersion"),
                str(item, "contentHash"),
                longVal(item, "createdAt", 0L),
                longVal(item, "expiresAt", 0L),
                intValOrNull(item, "cachedTokens"),
                entryType);
    }

    private static String str(Map<String, AttributeValue> m, String k) {
        return m.containsKey(k) ? m.get(k).s() : null;
    }

    private static long longVal(Map<String, AttributeValue> m, String k, long def) {
        try {
            return m.containsKey(k) ? Long.parseLong(m.get(k).n()) : def;
        } catch (Exception e) {
            LOG.warnf("Unparseable long for %s: %s", k, m.get(k));
            return def;
        }
    }

    private static Integer intValOrNull(Map<String, AttributeValue> m, String k) {
        try {
            return m.containsKey(k) ? Integer.valueOf(m.get(k).n()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(String.valueOf(value)).build();
    }
}

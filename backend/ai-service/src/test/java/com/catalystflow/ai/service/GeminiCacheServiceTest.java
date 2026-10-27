package com.catalystflow.ai.service;

import com.catalystflow.ai.repository.GeminiContextCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CC-2 — pure unit tests for {@link GeminiCacheService}.
 *
 * <p>Covers the AC-8.5 scenarios plus the AC-8.4 step-5 degrade contract,
 * the 60s expiry-soon refresh, and the countTokens preflight short-circuit.
 * Pure unit test: mocks {@link GeminiContextCacheRepository} and
 * {@link GeminiCacheTransport}, wires them into the service by reflection.
 */
class GeminiCacheServiceTest {

    private GeminiCacheService svc;
    private GeminiContextCacheRepository repo;
    private GeminiCacheTransport cacheTransport;

    @BeforeEach
    void setUp() throws Exception {
        svc = new GeminiCacheService();
        repo = mock(GeminiContextCacheRepository.class);
        cacheTransport = mock(GeminiCacheTransport.class);
        setField(svc, "repository", repo);
        setField(svc, "cacheTransport", cacheTransport);
    }

    // ── AC-8.5 #1: hit ───────────────────────────────────────────────────────

    @Test
    void resolveOrCreate_hit_returnsExistingCacheName_noTransportCall() {
        long now = Instant.now().getEpochSecond();
        when(repo.get(anyString())).thenReturn(Optional.of(entry(
                "cachedContents/abc-existing", now + 3600)));

        String result = svc.resolveOrCreate(request(3600));

        assertEquals("cachedContents/abc-existing", result);
        verify(cacheTransport, never()).countTokens(anyString(), anyString());
        verify(cacheTransport, never()).callCachedContents(anyString(), anyString(), anyString(), anyLong());
        verify(repo, never()).put(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyLong(), anyLong(), any(), anyString());
    }

    // ── AC-8.5 #2: miss-then-create (with preflight passing) ─────────────────

    @Test
    void resolveOrCreate_miss_preflightPasses_createsCacheAndPersists() {
        when(repo.get(anyString())).thenReturn(Optional.empty());
        when(cacheTransport.countTokens(eq("gemini-2.5-flash"), anyString())).thenReturn(5000);
        when(cacheTransport.callCachedContents(eq("gemini-2.5-flash"),
                anyString(), anyString(), eq(3600L)))
                .thenReturn(new GeminiCacheTransport.CacheCreateResult("cachedContents/new-xyz", 1800));

        String result = svc.resolveOrCreate(request(3600));

        assertEquals("cachedContents/new-xyz", result);
        ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> cachedCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> entryTypeCap = ArgumentCaptor.forClass(String.class);
        verify(repo, times(1)).put(keyCap.capture(), eq("cachedContents/new-xyz"),
                eq("ANALYZE"), eq("ANALYZE_V2"),
                anyString(), anyLong(), anyLong(), cachedCap.capture(),
                entryTypeCap.capture());
        assertEquals(Integer.valueOf(1800), cachedCap.getValue(),
                "cachedTokens from Gemini response must persist for CC-5 cost dashboards");
        assertTrue(keyCap.getValue().startsWith("ANALYZE#ANALYZE_V2#"),
                "cacheKey starts with taskType + promptVersion: " + keyCap.getValue());
        // CC-4b follow-up MEDIUM: real Gemini cache rows must persist with
        // entryType=CACHE so CC-6's planned admin DELETE knows to call Gemini.
        assertEquals(GeminiContextCacheRepository.ENTRY_TYPE_CACHE, entryTypeCap.getValue(),
                "Real cache rows must persist entryType=CACHE (vs UNDER_THRESHOLD for tombstones)");

        // MEDIUM #2 follow-up: assert systemPrompt + extraPrefix passed SEPARATELY
        // to the transport — not concatenated. Pre-fix the service flattened them
        // into one string for cachedContents, demoting the system instruction to
        // user-role text.
        ArgumentCaptor<String> sysCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> prefixCap = ArgumentCaptor.forClass(String.class);
        verify(cacheTransport, times(1)).callCachedContents(eq("gemini-2.5-flash"),
                sysCap.capture(), prefixCap.capture(), eq(3600L));
        assertEquals("You generate performance review suggestions...", sysCap.getValue(),
                "systemPrompt must be passed as the dedicated systemInstruction parameter");
        assertEquals("Performance criteria for Engineer / DevOps:\n- foo\n- bar",
                prefixCap.getValue(),
                "extraPrefix must be passed as the dedicated user-role contents parameter");
    }

    // ── AC-8.5 #3: preflight short-circuits when token count < 1500 ──────────

    @Test
    void resolveOrCreate_preflightUnder1500_skipsCachedContents_persistsTombstone() {
        // Plan §D6: skip cache create when countTokens < 1500. Saves the
        // guaranteed-to-fail round-trip + the "min_total_token_count"
        // billing surprise on the small input.
        //
        // CC-4b follow-up: ALSO persist a negative-cache "tombstone" row
        // (cacheName = UNDER_THRESHOLD_SENTINEL) so the next call with the
        // same cacheKey skips countTokens. Without this memo, every cache-on
        // call for a known-under-threshold task (GOLDEN/DIGEST under CC-4b)
        // burns a countTokens round-trip whose result is fixed.
        when(repo.get(anyString())).thenReturn(Optional.empty());
        when(cacheTransport.countTokens(anyString(), anyString())).thenReturn(1499);

        String result = svc.resolveOrCreate(request(3600));

        assertNull(result, "Under-threshold content must not be cached "
                + "(sentinel must NOT leak to caller as a real cacheName)");
        verify(cacheTransport, never()).callCachedContents(anyString(), anyString(), anyString(), anyLong());

        // Tombstone write asserted: sentinel cacheName + recorded tokenCount
        // + explicit entryType=UNDER_THRESHOLD discriminator. The sentinel
        // cacheName is informational (visible in DDB inspectors); entryType
        // is the source of truth that admin / list / delete code reads.
        ArgumentCaptor<String> nameCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> cachedCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> entryTypeCap = ArgumentCaptor.forClass(String.class);
        verify(repo, times(1)).put(anyString(), nameCap.capture(),
                eq("ANALYZE"), eq("ANALYZE_V2"),
                anyString(), anyLong(), anyLong(), cachedCap.capture(),
                entryTypeCap.capture());
        assertEquals(GeminiCacheService.UNDER_THRESHOLD_SENTINEL, nameCap.getValue(),
                "Tombstone row uses the sentinel cacheName for forensic visibility");
        assertEquals(GeminiContextCacheRepository.ENTRY_TYPE_UNDER_THRESHOLD,
                entryTypeCap.getValue(),
                "Tombstone row MUST persist entryType=UNDER_THRESHOLD so admin DELETE "
                        + "(CC-6) skips the Gemini DELETE call — no upstream resource exists");
        assertEquals(Integer.valueOf(1499), cachedCap.getValue(),
                "Tombstone records the actual token count for observability");
    }

    @Test
    void resolveOrCreate_existingTombstone_returnsNull_withoutCallingCountTokens() {
        // CC-4b follow-up: the tombstone's whole point. When a prior call has
        // already memo'd "this cacheKey is under-threshold", the next call
        // must skip countTokens entirely. Pre-fix every call to a known-small
        // cacheKey burned a fresh countTokens — N calls = N countTokens
        // round-trips for a result that's fixed.
        long now = Instant.now().getEpochSecond();
        when(repo.get(anyString())).thenReturn(Optional.of(tombstoneEntry(now + 3600)));

        String result = svc.resolveOrCreate(request(3600));

        assertNull(result,
                "Tombstone-hit must surface as null to the caller — the sentinel is "
                        + "an internal marker, not a real Gemini cacheName");
        verify(cacheTransport, never()).countTokens(anyString(), anyString());
        verify(cacheTransport, never()).callCachedContents(anyString(), anyString(),
                anyString(), anyLong());
        // No new put — the tombstone is already present.
        verify(repo, never()).put(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void resolveOrCreate_countTokensReturnsNull_doesNotPersistTombstone() {
        // Negative-cache hygiene: when countTokens itself fails (HTTP error,
        // Gemini 5xx, parse drift), we don't KNOW the size — persisting a
        // tombstone would lock in "too small" for the TTL based on no data.
        // Better to degrade THIS call to uncached and let the next call
        // re-try countTokens.
        when(repo.get(anyString())).thenReturn(Optional.empty());
        when(cacheTransport.countTokens(anyString(), anyString())).thenReturn(null);

        String result = svc.resolveOrCreate(request(3600));

        assertNull(result);
        // No tombstone write — token count is unknown.
        verify(repo, never()).put(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void resolveOrCreate_preflightAtThreshold_proceedsToCreate() {
        // Boundary: exactly 1500 is the lower bound that proceeds (>=). One
        // off-by-one here would either over- or under-cache the marginal cases.
        when(repo.get(anyString())).thenReturn(Optional.empty());
        when(cacheTransport.countTokens(anyString(), anyString())).thenReturn(1500);
        when(cacheTransport.callCachedContents(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new GeminiCacheTransport.CacheCreateResult("cachedContents/at-threshold", null));

        String result = svc.resolveOrCreate(request(3600));

        assertEquals("cachedContents/at-threshold", result);
        verify(cacheTransport, times(1)).callCachedContents(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void resolveOrCreate_preflightReturnsNull_degradesToUncached() {
        // countTokens itself failed (IO / parse / 4xx). Service can't proceed
        // to callCachedContents blindly because it would be paying for an
        // unknown-cost call. Degrade.
        when(repo.get(anyString())).thenReturn(Optional.empty());
        when(cacheTransport.countTokens(anyString(), anyString())).thenReturn(null);

        String result = svc.resolveOrCreate(request(3600));

        assertNull(result);
        verify(cacheTransport, never()).callCachedContents(anyString(), anyString(), anyString(), anyLong());
    }

    // ── AC-8.5 #4: cacheCreate returns null (Gemini 4xx) → null ──────────────

    @Test
    void resolveOrCreate_cacheCreateReturnsNull_degradesToUncached() {
        when(repo.get(anyString())).thenReturn(Optional.empty());
        when(cacheTransport.countTokens(anyString(), anyString())).thenReturn(5000);
        when(cacheTransport.callCachedContents(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(null);

        String result = svc.resolveOrCreate(request(3600));

        assertNull(result);
        verify(repo, never()).put(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyLong(), anyLong(), any(), anyString());
    }

    // ── AC-8.5 #5: transport throws unexpectedly → null ──────────────────────

    @Test
    void resolveOrCreate_cacheCreateThrows_degradesToUncached() {
        when(repo.get(anyString())).thenReturn(Optional.empty());
        when(cacheTransport.countTokens(anyString(), anyString())).thenReturn(5000);
        when(cacheTransport.callCachedContents(anyString(), anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("unexpected"));

        String result = svc.resolveOrCreate(request(3600));

        assertNull(result);
    }

    @Test
    void resolveOrCreate_countTokensThrows_degradesToUncached() {
        when(repo.get(anyString())).thenReturn(Optional.empty());
        when(cacheTransport.countTokens(anyString(), anyString()))
                .thenThrow(new RuntimeException("unexpected"));

        String result = svc.resolveOrCreate(request(3600));

        assertNull(result);
        verify(cacheTransport, never()).callCachedContents(anyString(), anyString(), anyString(), anyLong());
    }

    // ── AC-8.5 #6: DDB outage on get → null ──────────────────────────────────

    @Test
    void resolveOrCreate_ddbGetThrows_degradesToUncached() {
        when(repo.get(anyString())).thenThrow(new RuntimeException("DDB outage"));

        String result = svc.resolveOrCreate(request(3600));

        assertNull(result);
        verify(cacheTransport, never()).countTokens(anyString(), anyString());
        verify(cacheTransport, never()).callCachedContents(anyString(), anyString(), anyString(), anyLong());
    }

    // ── 60s expiry-soon refresh ──────────────────────────────────────────────

    @Test
    void resolveOrCreate_existingButExpiresSoon_treatsAsMiss_createsFresh() {
        long now = Instant.now().getEpochSecond();
        when(repo.get(anyString())).thenReturn(Optional.of(entry(
                "cachedContents/about-to-expire", now + 30)));
        when(cacheTransport.countTokens(anyString(), anyString())).thenReturn(5000);
        when(cacheTransport.callCachedContents(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new GeminiCacheTransport.CacheCreateResult("cachedContents/fresh", null));

        String result = svc.resolveOrCreate(request(3600));

        assertEquals("cachedContents/fresh", result,
                "expiry-soon row must NOT be reused — fresh create required");
        verify(cacheTransport, times(1)).callCachedContents(anyString(), anyString(), anyString(), anyLong());
    }

    // ── AC-8.4 step 5: post-put failure → null ───────────────────────────────

    @Test
    void resolveOrCreate_postCreatePutFails_returnsNull_perAC84() {
        // AC-8.4 step 5: ANY failure -> null. Even though the Gemini cache
        // exists upstream, a cacheName we can't remember is worse than null —
        // next request re-creates a fresh Gemini cache, the orphaned one
        // burns storage for its TTL with no DDB pointer back to it.
        when(repo.get(anyString())).thenReturn(Optional.empty());
        when(cacheTransport.countTokens(anyString(), anyString())).thenReturn(5000);
        when(cacheTransport.callCachedContents(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new GeminiCacheTransport.CacheCreateResult("cachedContents/created", 1000));
        org.mockito.Mockito.doThrow(new RuntimeException("DDB write outage"))
                .when(repo).put(anyString(), anyString(), anyString(),
                        anyString(), anyString(), anyLong(), anyLong(), any(),
                        anyString());

        String result = svc.resolveOrCreate(request(3600));

        assertNull(result,
                "AC-8.4 step 5: post-put DDB failure must surface as null (degrade-to-uncached)");
    }

    // ── cacheKey composition ─────────────────────────────────────────────────

    @Test
    void composeCacheKey_includesTaskTypeAndPromptVersionAndDisciplineAndSeniority() {
        String key = GeminiCacheService.composeCacheKey(request(3600));
        // SPEC cachekey-include-model: key is now 6 segments —
        // taskType#promptVersion#hash#discipline#seniority#model.
        String[] p = key.split("#", -1);
        assertEquals(6, p.length, "6 key segments incl. trailing model: " + key);
        assertEquals("ANALYZE", p[0]);
        assertEquals("ANALYZE_V2", p[1]);
        assertEquals("Software & DevOps Engineering", p[3]);
        assertEquals("Engineer", p[4]);
        assertEquals("gemini-2.5-flash", p[5], "model is the final key segment");
    }

    @Test
    void composeCacheKey_differentPromptsProduceDifferentKeys() {
        CacheRequest a = new CacheRequest("ANALYZE", "ANALYZE_V2",
                "DevOps", "Engineer", "prompt A", "extra A",
                "gemini-2.5-flash", 3600);
        CacheRequest b = new CacheRequest("ANALYZE", "ANALYZE_V2",
                "DevOps", "Engineer", "prompt B", "extra A",
                "gemini-2.5-flash", 3600);

        assertNotEquals(GeminiCacheService.composeCacheKey(a),
                GeminiCacheService.composeCacheKey(b),
                "Different system prompts must produce different content-hash → different keys");
    }

    @Test
    void composeCacheKey_nullDisciplineAndSeniority_renderAsEmpty() {
        CacheRequest req = new CacheRequest("MANAGER_GOLDEN", "MANAGER_GOLDEN_V2",
                null, null, "system", null, "gemini-2.5-flash", 600);
        String key = GeminiCacheService.composeCacheKey(req);
        String[] p = key.split("#", -1);
        assertEquals(6, p.length, "6 segments incl. trailing model: " + key);
        assertEquals("", p[3], "null discipline → empty segment");
        assertEquals("", p[4], "null seniority → empty segment");
        assertEquals("gemini-2.5-flash", p[5], "model still appended after empty discipline/seniority");
    }

    @Test
    void composeCacheKey_distinctModels_produceDistinctKeys() {
        // Regression guard for the cross-model cachedContent 400 (job 82d9b1f7):
        // identical task/prompt/discipline/seniority but a DIFFERENT model must
        // NOT collide on the same cache row.
        CacheRequest flash25 = new CacheRequest("INSIGHTS", "INSIGHTS_V2",
                "DevOps", "Engineer", "sys", "extra", "gemini-2.5-flash", 1800);
        CacheRequest flash35 = new CacheRequest("INSIGHTS", "INSIGHTS_V2",
                "DevOps", "Engineer", "sys", "extra", "gemini-3.5-flash", 1800);
        assertNotEquals(GeminiCacheService.composeCacheKey(flash25),
                GeminiCacheService.composeCacheKey(flash35),
                "different model MUST produce a different cache key (else cross-model cachedContent 400)");
    }

    @Test
    void composeCacheKey_normalizesModelsPrefix() {
        // "models/X" and "X" denote the same model — they must map to the same
        // key so a caller's prefix style doesn't fork the cache.
        CacheRequest bare = new CacheRequest("INSIGHTS", "INSIGHTS_V2",
                "DevOps", "Engineer", "sys", "extra", "gemini-2.5-flash", 1800);
        CacheRequest prefixed = new CacheRequest("INSIGHTS", "INSIGHTS_V2",
                "DevOps", "Engineer", "sys", "extra", "models/gemini-2.5-flash", 1800);
        assertEquals(GeminiCacheService.composeCacheKey(bare),
                GeminiCacheService.composeCacheKey(prefixed),
                "the models/ prefix must be normalized out of the cache key");
    }

    // ── CC-6 admin: delete + listAll ────────────────────────────────────────

    @Test
    void delete_existingCacheRow_ddbFirstThenGemini_returnsTrue() {
        // CC-6 follow-up: order is DDB delete FIRST, then best-effort Gemini
        // DELETE. Reversed from the original implementation. Asserted via
        // Mockito InOrder so any future edit that flips the order trips
        // this test loudly.
        long now = Instant.now().getEpochSecond();
        when(repo.get(anyString())).thenReturn(Optional.of(entry(
                "cachedContents/abc-real", now + 3600)));

        boolean existed = svc.delete("ANALYZE#ANALYZE_V2#0011223344556677#Software & DevOps Engineering#Engineer");

        assertTrue(existed, "Existing row must surface as true → admin endpoint returns 204");
        org.mockito.InOrder ordered = org.mockito.Mockito.inOrder(repo, cacheTransport);
        ordered.verify(repo).delete(eq("ANALYZE#ANALYZE_V2#0011223344556677#Software & DevOps Engineering#Engineer"));
        ordered.verify(cacheTransport).deleteCachedContent(eq("cachedContents/abc-real"));
    }

    @Test
    void delete_ddbDeleteThrows_geminiDeleteNotCalled_propagates() {
        // CC-6 follow-up MEDIUM #1 regression guard. Pre-fix the order was
        // Gemini-then-DDB: if DDB delete failed after Gemini DELETE succeeded,
        // the DDB row would point at a deleted upstream resource and the
        // next resolveOrCreate would hand GeminiClient a stale cacheName →
        // user-facing 4xx on every cached call until DDB TTL.
        //
        // Post-fix: DDB delete runs FIRST. If it throws, Gemini DELETE is
        // NEVER called — the pointer-to-deleted-resource window is closed.
        // Operator gets a 500 and retries.
        long now = Instant.now().getEpochSecond();
        when(repo.get(anyString())).thenReturn(Optional.of(entry(
                "cachedContents/abc-real", now + 3600)));
        org.mockito.Mockito.doThrow(new RuntimeException("DDB delete outage"))
                .when(repo).delete(anyString());

        assertThrows(IllegalStateException.class,
                () -> svc.delete("ANALYZE#ANALYZE_V2#0011223344556677#Software & DevOps Engineering#Engineer"),
                "DDB delete failure must propagate as IllegalStateException so admin endpoint 500s");

        verify(cacheTransport, never()).deleteCachedContent(anyString());
    }

    @Test
    void delete_existingTombstone_skipsGeminiDelete_butStillRemovesDdbRow() {
        // CC-4b round-2 contract: UNDER_THRESHOLD tombstones have no upstream
        // Gemini resource to delete — calling Gemini DELETE would 404 every
        // time. Admin DELETE must skip the upstream call but still remove the
        // DDB row (so the next resolveOrCreate triggers a fresh countTokens).
        long now = Instant.now().getEpochSecond();
        when(repo.get(anyString())).thenReturn(Optional.of(tombstoneEntry(now + 600)));

        boolean existed = svc.delete("ANALYZE#ANALYZE_V2#0011223344556677#Software & DevOps Engineering#Engineer");

        assertTrue(existed, "Tombstone row counts as 'existed' — admin gets 204");
        verify(cacheTransport, never()).deleteCachedContent(anyString());
        verify(repo, times(1)).delete(anyString());
    }

    @Test
    void delete_missingRow_returnsFalse_noGeminiNoDdbDelete() {
        when(repo.get(anyString())).thenReturn(Optional.empty());

        boolean existed = svc.delete("nope#nope#nope##");

        org.junit.jupiter.api.Assertions.assertFalse(existed,
                "Missing row → false so admin endpoint maps to 404");
        verify(cacheTransport, never()).deleteCachedContent(anyString());
        verify(repo, never()).delete(anyString());
    }

    @Test
    void delete_geminiTransportThrows_swallowedThenDdbDelete_stillReturnsTrue() {
        // Transport is contracted to never throw, but defence-in-depth: if a
        // future change leaks an exception, the admin DELETE must still
        // remove the DDB row and return success. The orphaned upstream cache
        // ages out via Gemini's TTL.
        long now = Instant.now().getEpochSecond();
        when(repo.get(anyString())).thenReturn(Optional.of(entry(
                "cachedContents/leaks", now + 3600)));
        org.mockito.Mockito.doThrow(new RuntimeException("simulated leak"))
                .when(cacheTransport).deleteCachedContent(anyString());

        boolean existed = svc.delete("ANALYZE#ANALYZE_V2#0011223344556677#Software & DevOps Engineering#Engineer");

        assertTrue(existed, "Upstream Gemini throw must not fail the admin DELETE");
        verify(repo, times(1)).delete(anyString());
    }

    @Test
    void delete_ddbGetThrows_propagatesAsIllegalStateException() {
        // DDB get failure is a real problem — the admin endpoint should
        // surface it as 500, not silently no-op. Service throws IllegalStateException
        // and the resource translates to MessageResponse + 500.
        when(repo.get(anyString())).thenThrow(new RuntimeException("DDB outage"));

        assertThrows(IllegalStateException.class,
                () -> svc.delete("anything"));
        verify(cacheTransport, never()).deleteCachedContent(anyString());
        verify(repo, never()).delete(anyString());
    }

    @Test
    void listAll_returnsAllRowsFromRepo() {
        when(repo.listAll()).thenReturn(java.util.List.of(
                entry("cachedContents/one", 1L),
                tombstoneEntry(2L),
                entry("cachedContents/two", 3L)
        ));

        java.util.List<GeminiContextCacheRepository.CacheEntry> rows = svc.listAll();

        assertEquals(3, rows.size());
        verify(repo, times(1)).listAll();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static CacheRequest request(long ttlSeconds) {
        return new CacheRequest(
                "ANALYZE", "ANALYZE_V2",
                "Software & DevOps Engineering", "Engineer",
                "You generate performance review suggestions...",
                "Performance criteria for Engineer / DevOps:\n- foo\n- bar",
                "gemini-2.5-flash",
                ttlSeconds);
    }

    private static GeminiContextCacheRepository.CacheEntry entry(String cacheName, long expiresAt) {
        return new GeminiContextCacheRepository.CacheEntry(
                "ANALYZE#ANALYZE_V2#0011223344556677#Software & DevOps Engineering#Engineer",
                cacheName, "ANALYZE", "ANALYZE_V2",
                "0011223344556677", expiresAt - 3600, expiresAt, 1500,
                GeminiContextCacheRepository.ENTRY_TYPE_CACHE);
    }

    /**
     * Mirror of {@link #entry} but for a NEGATIVE-cache (tombstone) row:
     * cacheName = sentinel, entryType = UNDER_THRESHOLD, cachedTokens =
     * realistic under-threshold value. Used by tests proving the tombstone
     * short-circuits countTokens AND that the discriminator (not the
     * sentinel-string comparison) is what the lookup branches on.
     */
    private static GeminiContextCacheRepository.CacheEntry tombstoneEntry(long expiresAt) {
        return new GeminiContextCacheRepository.CacheEntry(
                "ANALYZE#ANALYZE_V2#0011223344556677#Software & DevOps Engineering#Engineer",
                GeminiCacheService.UNDER_THRESHOLD_SENTINEL,
                "ANALYZE", "ANALYZE_V2",
                "0011223344556677", expiresAt - 3600, expiresAt, 700,
                GeminiContextCacheRepository.ENTRY_TYPE_UNDER_THRESHOLD);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}

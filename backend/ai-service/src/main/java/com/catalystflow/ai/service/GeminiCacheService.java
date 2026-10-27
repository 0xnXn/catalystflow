package com.catalystflow.ai.service;

import com.catalystflow.ai.repository.GeminiContextCacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Optional;

/**
 * Resolves a {@link CacheRequest} to a Gemini {@code cachedContents/<id>}
 * resource name, creating the Gemini cache on miss. CC-3 wires this in
 * front of {@link GeminiClient#call}; CC-4..CC-6 add per-task wiring,
 * telemetry, and admin invalidation respectively.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>On hit (DDB row exists AND {@code expiresAt > now + 60s}): return
 *       the existing {@code cacheName}.</li>
 *   <li>On miss: call {@link GeminiClient#callCachedContents} to create
 *       a fresh Gemini cache, persist the row, return the new
 *       {@code cacheName}.</li>
 *   <li>On ANY failure (DDB outage, network IO, Gemini 4xx for too-small
 *       content): log + return {@code null}. The caller falls through to
 *       the uncached path. Never throws.</li>
 * </ul>
 *
 * <h2>Why the 60s expiry guard</h2>
 * A cache row whose {@code expiresAt} is 30 seconds away might be reaped
 * by Gemini before the next {@code generateContent} call completes. The
 * 60-second guard ensures any cacheName we return is still good for the
 * duration of the downstream call. A would-expire row is treated as a
 * miss and triggers a fresh create.
 *
 * <h2>cacheKey composition (AC-8.3)</h2>
 * <pre>
 *   taskType + "#" + promptVersion + "#" + sha256_16(systemPrompt + extraPrefix)
 *            + "#" + nz(discipline) + "#" + nz(seniority)
 * </pre>
 * Including the SHA-16 of the content text means a prompt edit that
 * forgets to bump {@code promptVersion} still produces a fresh cache —
 * belt-and-braces against accidental cache poisoning.
 */
@ApplicationScoped
public class GeminiCacheService {

    private static final Logger LOG = Logger.getLogger(GeminiCacheService.class);

    /**
     * Don't return a cache row whose Gemini-side TTL is within this window
     * of expiring — the cache could be reaped mid-call. Treat as a miss
     * and force a fresh create.
     */
    static final long EXPIRY_GUARD_SECONDS = 60L;

    /**
     * AC-8.5 preflight threshold. Gemini's minimum cacheable size is 1024
     * tokens per the API docs — we keep a 1500-token safety margin because:
     * <ul>
     *   <li>Per-model minima can vary (1024 is the documented floor; some
     *       models enforce higher).</li>
     *   <li>{@code countTokens} returns the canonical token count for a
     *       fixed content string; the actual cacheable boundary on Gemini's
     *       end may differ by a few tokens for metadata overhead.</li>
     * </ul>
     * Below this threshold we skip {@link GeminiCacheTransport#callCachedContents}
     * entirely — saves the guaranteed-to-fail round-trip and the
     * "min_total_token_count" billing surprise on the small input we'd
     * have sent.
     */
    static final int MIN_CACHEABLE_TOKENS = 1500;

    /**
     * Re-export of {@link GeminiContextCacheRepository#UNDER_THRESHOLD_SENTINEL}
     * kept for in-service code clarity (tombstone write site reads as
     * {@code repository.put(..., UNDER_THRESHOLD_SENTINEL, ..., ENTRY_TYPE_UNDER_THRESHOLD)}
     * without forcing a fully-qualified reference). The single source of
     * truth lives on the repository — see that constant's Javadoc for
     * rationale.
     *
     * <p>Why memoise: §D6 of the plan called for {@code countTokens}
     * memoisation per cacheKey. Without it, every cache-on call for a
     * known-under-threshold task (today: GOLDEN + DIGEST per CC-4b) burns a
     * countTokens round-trip whose result is fixed for the cacheKey's
     * lifetime. The tombstone makes the {@code N} repeat calls cost {@code 1}
     * countTokens + {@code N-1} DDB GetItems.
     */
    static final String UNDER_THRESHOLD_SENTINEL = GeminiContextCacheRepository.UNDER_THRESHOLD_SENTINEL;

    @Inject
    GeminiContextCacheRepository repository;

    @Inject
    GeminiCacheTransport cacheTransport;

    /**
     * Look up or create the Gemini cache for {@code req}. Returns the
     * Gemini {@code cachedContents/<id>} resource name on success, or
     * {@code null} on ANY failure (with diagnostics logged).
     *
     * <p>Never throws. Callers wrap the call with no try/catch.
     */
    public String resolveOrCreate(CacheRequest req) {
        String cacheKey;
        try {
            cacheKey = composeCacheKey(req);
        } catch (Exception e) {
            // sha-256 should never fail on JDK 21; if it does we still want
            // to degrade gracefully rather than fail the user-facing job.
            LOG.warnf(e, "Gemini cache key composition failed; degrading to uncached");
            return null;
        }

        // Step 1: cache lookup
        Optional<GeminiContextCacheRepository.CacheEntry> existing;
        try {
            existing = repository.get(cacheKey);
        } catch (Exception e) {
            LOG.warnf(e, "Gemini cache DDB get failed for %s; degrading to uncached", cacheKey);
            return null;
        }

        long now = Instant.now().getEpochSecond();
        if (existing.isPresent()
                && existing.get().expiresAt() > now + EXPIRY_GUARD_SECONDS) {
            GeminiContextCacheRepository.CacheEntry hit = existing.get();
            // Tombstone short-circuit: a prior call already measured this
            // cacheKey's content as under-threshold and persisted a negative
            // entry. Return null WITHOUT calling countTokens — that's the
            // whole point of the memo.
            //
            // CC-4b follow-up MEDIUM: read the explicit entryType field
            // (NOT a sentinel-string comparison on cacheName). The
            // cacheName column still holds UNDER_THRESHOLD_SENTINEL for
            // forensic visibility, but entryType is the source of truth
            // for "is this a usable cache row" everywhere — including the
            // future CC-6 admin DELETE path which must skip Gemini DELETE
            // for tombstones (no upstream resource to delete).
            if (GeminiContextCacheRepository.ENTRY_TYPE_UNDER_THRESHOLD.equals(hit.entryType())) {
                LOG.debugf("Gemini cache NEGATIVE-HIT (under-threshold tombstone): "
                        + "key=%s tokens=%s expiresIn=%ds — skipping countTokens",
                        cacheKey, hit.cachedTokens(), hit.expiresAt() - now);
                return null;
            }
            LOG.debugf("Gemini cache HIT: key=%s name=%s expiresIn=%ds",
                    cacheKey, hit.cacheName(), hit.expiresAt() - now);
            return hit.cacheName();
        }

        // Step 2: cache miss — preflight token count, then create
        String content = req.systemPrompt() + nz(req.extraPrefix());

        // AC-8.5 preflight: countTokens before cachedContents. Skip the
        // create POST entirely if the content is below the safety threshold —
        // Gemini would 4xx with min_total_token_count anyway, and we'd still
        // be billed for the small input. countTokens itself is much cheaper.
        Integer tokenCount;
        try {
            tokenCount = cacheTransport.countTokens(req.model(), content);
        } catch (Exception e) {
            LOG.warnf(e, "Gemini countTokens threw for %s; degrading to uncached", cacheKey);
            return null;
        }
        if (tokenCount == null) {
            // countTokens itself failed (IO / parse / 4xx). Degrade — don't
            // guess by trying the cache-create POST blindly.
            //
            // ALSO: do NOT persist a tombstone here. The token count is
            // unknown; a tombstone would lock in "too small" for a TTL we
            // can't justify. Better to retry countTokens next call.
            return null;
        }
        if (tokenCount < MIN_CACHEABLE_TOKENS) {
            // Persist a negative cache entry so repeat calls with this exact
            // cacheKey skip countTokens for the row's TTL. The sentinel
            // cacheName is the marker; the actual tokenCount is recorded for
            // observability (operator can see "tombstone says 700 tokens").
            //
            // Best-effort: if the put fails (DDB outage), we just don't
            // memoise this round — the next call will burn another
            // countTokens, same behaviour as before this memoisation
            // existed. Not worth degrading the user-facing call over a
            // failed observability write.
            long tombstoneExpires = now + req.ttlSeconds();
            try {
                repository.put(cacheKey, UNDER_THRESHOLD_SENTINEL,
                        req.taskType(), req.promptVersion(),
                        contentHash16(content), now, tombstoneExpires,
                        tokenCount,
                        GeminiContextCacheRepository.ENTRY_TYPE_UNDER_THRESHOLD);
                LOG.debugf("Gemini cache TOMBSTONE-PERSIST: key=%s tokens=%d threshold=%d ttl=%ds",
                        cacheKey, tokenCount, MIN_CACHEABLE_TOKENS, req.ttlSeconds());
            } catch (Exception e) {
                LOG.warnf(e, "Gemini cache tombstone write failed for %s; under-threshold "
                        + "memo not persisted (next call will repeat countTokens)", cacheKey);
            }
            return null;
        }

        GeminiCacheTransport.CacheCreateResult created;
        try {
            // CC-4a follow-up: pass systemPrompt + extraPrefix separately so
            // the cache stores them with correct role semantics
            // (systemInstruction + user content). The `content` string above
            // is still used for the contentHash + countTokens preflight —
            // those are about size+identity, not Gemini-side roles.
            created = cacheTransport.callCachedContents(req.model(),
                    req.systemPrompt(), nz(req.extraPrefix()), req.ttlSeconds());
        } catch (Exception e) {
            // Transport is contracted to return null rather than throw, but
            // defence-in-depth — if a future change leaks an exception, still
            // degrade gracefully.
            LOG.warnf(e, "Gemini cachedContents create threw for %s; degrading to uncached", cacheKey);
            return null;
        }
        if (created == null) {
            // Transport already logged the failure detail. Don't double-log.
            return null;
        }

        // Step 3: persist the binding
        long expiresAt = now + req.ttlSeconds();
        try {
            repository.put(cacheKey, created.cacheName(),
                    req.taskType(), req.promptVersion(),
                    contentHash16(content), now, expiresAt,
                    created.cachedTokens(),
                    GeminiContextCacheRepository.ENTRY_TYPE_CACHE);
        } catch (Exception e) {
            // AC-8.4 step 5: degrade-to-uncached on ANY failure. The Gemini
            // cache exists upstream — there's a cost argument for returning
            // its name anyway (the immediate call would benefit) — but the
            // strict contract is null on any layer breaking. Rationale: a
            // returned-but-unpersisted name produces no read on the next
            // request, the next request re-creates a fresh Gemini cache,
            // and the orphaned upstream cache lives out its TTL paying for
            // storage we can't reuse. Cleaner to never return a cacheName
            // we couldn't reliably remember.
            LOG.warnf(e, "Gemini cache DDB put failed for %s; degrading to uncached (AC-8.4)",
                    cacheKey);
            return null;
        }
        LOG.infof("Gemini cache MISS+CREATE: key=%s name=%s ttl=%ds", cacheKey, created.cacheName(), req.ttlSeconds());
        return created.cacheName();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static String composeCacheKey(CacheRequest req) {
        String hashed = contentHash16(req.systemPrompt() + nz(req.extraPrefix()));
        // The model is part of the key: a Gemini cachedContents/<id> is bound to
        // the model it was created under, so reusing one across models yields a
        // 400 ("Model … and CachedContent … has to be the same" — job 82d9b1f7).
        // Keying by model gives each model its own rows (e.g. the SPEC-503
        // fallback to gemini-2.5-flash never collides with primary-model rows).
        // Normalized so "models/X" and "X" don't fork the cache.
        return req.taskType() + "#"
                + req.promptVersion() + "#"
                + hashed + "#"
                + nz(req.discipline()) + "#"
                + nz(req.seniority()) + "#"
                + normalizeModelForKey(req.model());
    }

    /** Strip an optional {@code models/} prefix so both forms map to one key. */
    private static String normalizeModelForKey(String model) {
        String m = nz(model);
        return m.startsWith("models/") ? m.substring("models/".length()) : m;
    }

    static String contentHash16(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            // Only the first 8 bytes -> 16 hex chars. Collision risk is
            // negligible because the cacheKey also includes taskType +
            // promptVersion + discipline + seniority as distinguishers.
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by the JDK spec — this branch is unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ── CC-6 admin operations ────────────────────────────────────────────────

    /**
     * CC-6 admin DELETE — remove a cache row by its full cacheKey and, when
     * applicable, the upstream Gemini cachedContents resource.
     *
     * <h2>Behaviour by entry type</h2>
     * <ul>
     *   <li>{@link GeminiContextCacheRepository#ENTRY_TYPE_CACHE}: call
     *       {@link GeminiCacheTransport#deleteCachedContent} (best-effort —
     *       a 404 from Gemini is treated as success, see the transport's
     *       javadoc), then remove the DDB row.</li>
     *   <li>{@link GeminiContextCacheRepository#ENTRY_TYPE_UNDER_THRESHOLD}:
     *       skip the Gemini DELETE entirely — no upstream resource exists
     *       for tombstones, so calling Gemini would 404 every time. Just
     *       remove the DDB row.</li>
     * </ul>
     *
     * <p>Returns {@code true} iff a row existed (the DDB delete was
     * performed). {@code false} when no row exists for that cacheKey —
     * the admin endpoint maps this to 404.
     *
     * <p>Gemini DELETE failure (non-404) does NOT cause this method to
     * fail or throw — the DDB row is still removed so the next
     * {@link #resolveOrCreate} forces a fresh create. The orphaned upstream
     * cache will be reaped by Gemini's own TTL.
     */
    public boolean delete(String cacheKey) {
        Optional<GeminiContextCacheRepository.CacheEntry> existing;
        try {
            existing = repository.get(cacheKey);
        } catch (Exception e) {
            // DDB get itself failed — surface as a 500 from the caller so
            // operators see the real problem (not a silent no-op delete).
            // Caller (admin endpoint) is expected to catch and translate.
            LOG.warnf(e, "CC-6 admin delete: DDB get failed for %s — propagating", cacheKey);
            throw new IllegalStateException("DDB get failed for cacheKey " + cacheKey, e);
        }
        if (existing.isEmpty()) {
            return false;
        }
        GeminiContextCacheRepository.CacheEntry row = existing.get();

        // CC-6 follow-up: DDB delete BEFORE Gemini DELETE.
        //
        // Pre-fix order was Gemini-then-DDB. Failure mode: Gemini DELETE
        // succeeds, DDB delete then throws → the DDB row stays, pointing
        // at a now-deleted cachedContents/<id>. The next resolveOrCreate
        // returns that stale cacheName; GeminiClient sends
        // `cachedContent: cachedContents/<deleted-id>` → upstream 4xx →
        // user-facing failure on every cached call for that bucket until
        // DDB TTL eventually reaps the row.
        //
        // Post-fix order: DDB first. Failure modes:
        //   - DDB delete succeeds, Gemini DELETE fails → DDB has no pointer,
        //     next resolveOrCreate creates fresh; orphaned upstream cache
        //     lives out its TTL (we pay storage we can't reuse, but NO
        //     user-facing failure). Best-effort upstream DELETE — the
        //     transport already swallows non-404 / 404 / IO errors at WARN.
        //   - DDB delete fails → throws IllegalStateException, Gemini DELETE
        //     never called, admin endpoint translates to 500. State unchanged
        //     upstream and in DDB. Operator retries.
        // The pointer-to-deleted-resource window is closed.
        try {
            repository.delete(cacheKey);
        } catch (Exception e) {
            LOG.warnf(e, "CC-6 admin delete: DDB delete failed for %s — propagating "
                    + "(Gemini DELETE NOT attempted to avoid orphaning the DDB pointer)", cacheKey);
            throw new IllegalStateException("DDB delete failed for cacheKey " + cacheKey, e);
        }
        if (GeminiContextCacheRepository.ENTRY_TYPE_CACHE.equals(row.entryType())) {
            // Real Gemini cache — attempt upstream DELETE. Best-effort: the
            // transport swallows failures (logged at WARN). The DDB pointer
            // is already gone so orphan upstream caches age out via Gemini's
            // own TTL; we just take the storage cost.
            try {
                cacheTransport.deleteCachedContent(row.cacheName());
            } catch (Exception e) {
                // Defence-in-depth — transport is contracted not to throw.
                LOG.warnf(e, "CC-6 admin delete: Gemini DELETE threw for %s (%s); "
                        + "DDB row already removed — orphan upstream will TTL out",
                        cacheKey, row.cacheName());
            }
        } else {
            // UNDER_THRESHOLD tombstone — no upstream resource to delete.
            // Skipping the Gemini call also avoids the 404 noise in CW Logs.
            LOG.debugf("CC-6 admin delete: skipping Gemini DELETE for tombstone key=%s "
                    + "(entryType=%s)", cacheKey, row.entryType());
        }
        return true;
    }

    /**
     * CC-6 admin LIST — return every row in the cache table. Includes both
     * CACHE rows and UNDER_THRESHOLD tombstones; the admin UI can filter on
     * {@code entryType} if needed. See {@link GeminiContextCacheRepository#listAll}
     * for pagination notes.
     */
    public java.util.List<GeminiContextCacheRepository.CacheEntry> listAll() {
        return repository.listAll();
    }
}

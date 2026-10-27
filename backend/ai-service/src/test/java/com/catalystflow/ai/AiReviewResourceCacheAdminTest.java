package com.catalystflow.ai;

import com.catalystflow.ai.repository.GeminiContextCacheRepository;
import com.catalystflow.ai.service.GeminiCacheService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CC-6 AC-12.4 — admin RBAC + status code coverage for the cache admin
 * endpoints. The tombstone-vs-real cache delete branching lives in
 * {@code GeminiCacheServiceTest} (service layer); this test stays at the
 * resource layer and pins:
 *
 * <ul>
 *   <li>RBAC: only admin gets 200 / 204; manager and employee get 403 on
 *       BOTH endpoints (per plan §6 decision 2 — admin-only).</li>
 *   <li>Status codes: 200 list, 204 delete, 404 missing key, 500 on DDB
 *       failure.</li>
 *   <li>Wire shape: list response binds to {@code CacheEntryListResponse}
 *       with {@code count} matching {@code entries[]} size.</li>
 * </ul>
 */
@QuarkusTest
class AiReviewResourceCacheAdminTest {

    @InjectMock
    GeminiCacheService geminiCacheService;

    @InjectMock
    ClaimsContext claimsContext;

    @BeforeEach
    void setUp() {
        reset(geminiCacheService);
        // Default caller = employee (neither admin nor manager). Each test
        // that needs admin opts in via when(claimsContext.isAdmin()).thenReturn(true).
        when(claimsContext.getUserId()).thenReturn("employee-id");
    }

    // ── GET /ai-review/cache ────────────────────────────────────────────────

    /**
     * Scope note (CC-6 round-3 follow-up): the response is row inventory
     * only — no per-entry hit-rate. AC-12.2 originally promised hit-rate
     * stats; that was revised because per-entry tracking needs DDB counters
     * we don't ship today. Aggregate hit-rate is via the CC-5 CloudWatch
     * EMF metric CatalystFlow/AI/CacheHit. If you add hit-rate fields later,
     * they'll be additive on CacheEntryDto and these tests need extending.
     */
    @Test
    void listCacheEntries_asAdmin_returns200_withTypedEntries() {
        when(claimsContext.isAdmin()).thenReturn(true);
        when(geminiCacheService.listAll()).thenReturn(List.of(
                new GeminiContextCacheRepository.CacheEntry(
                        "ANALYZE#ANALYZE_V2#hash1#DevOps#Engineer",
                        "cachedContents/abc",
                        "ANALYZE", "ANALYZE_V2", "hash1",
                        1700000000L, 1700003600L, 3000,
                        GeminiContextCacheRepository.ENTRY_TYPE_CACHE),
                new GeminiContextCacheRepository.CacheEntry(
                        "MANAGER_GOLDEN#MANAGER_GOLDEN_V2#hash2##",
                        GeminiContextCacheRepository.UNDER_THRESHOLD_SENTINEL,
                        "MANAGER_GOLDEN", "MANAGER_GOLDEN_V2", "hash2",
                        1700000100L, 1700000700L, 700,
                        GeminiContextCacheRepository.ENTRY_TYPE_UNDER_THRESHOLD)
        ));

        given()
                .contentType(ContentType.JSON)
                .when().get("/ai-review/cache")
                .then()
                .statusCode(200)
                .body("count", equalTo(2))
                .body("entries", hasSize(2))
                .body("entries[0].cacheKey", equalTo("ANALYZE#ANALYZE_V2#hash1#DevOps#Engineer"))
                .body("entries[0].entryType", equalTo("CACHE"))
                .body("entries[0].cachedTokens", equalTo(3000))
                .body("entries[1].entryType", equalTo("UNDER_THRESHOLD"))
                .body("entries[1].cacheName", equalTo("[under-threshold]"));
    }

    @Test
    void listCacheEntries_asManager_returns403() {
        // Per AC-12.1/12.2: admin-only. Managers don't get cache visibility.
        when(claimsContext.isManager()).thenReturn(true);

        given()
                .contentType(ContentType.JSON)
                .when().get("/ai-review/cache")
                .then()
                .statusCode(403);

        verify(geminiCacheService, never()).listAll();
    }

    @Test
    void listCacheEntries_asEmployee_returns403() {
        // Default claimsContext = employee (no isAdmin / no isManager stub).
        given()
                .contentType(ContentType.JSON)
                .when().get("/ai-review/cache")
                .then()
                .statusCode(403);

        verify(geminiCacheService, never()).listAll();
    }

    @Test
    void listCacheEntries_emptyTable_returns200_emptyEntries() {
        when(claimsContext.isAdmin()).thenReturn(true);
        when(geminiCacheService.listAll()).thenReturn(List.of());

        given()
                .contentType(ContentType.JSON)
                .when().get("/ai-review/cache")
                .then()
                .statusCode(200)
                .body("count", equalTo(0))
                .body("entries", hasSize(0));
    }

    @Test
    void listCacheEntries_serviceThrows_returns500_withMessageResponse() {
        when(claimsContext.isAdmin()).thenReturn(true);
        when(geminiCacheService.listAll())
                .thenThrow(new IllegalStateException("DDB scan outage"));

        given()
                .contentType(ContentType.JSON)
                .when().get("/ai-review/cache")
                .then()
                .statusCode(500)
                .body("message", equalTo("Failed to list cache entries"));
    }

    // ── DELETE /ai-review/cache/{cacheKey} ──────────────────────────────────

    @Test
    void deleteCacheEntry_asAdmin_existingKey_returns204() {
        // Use a simple alphanumeric cacheKey to keep the URL routing
        // unambiguous. Real cacheKeys contain '#' which has to be url-encoded
        // as %23 by API Gateway clients — but Quarkus JAX-RS path-param
        // decoding handles that uniformly; we don't need to exercise it
        // here since the routing logic isn't what this test pins.
        when(claimsContext.isAdmin()).thenReturn(true);
        when(geminiCacheService.delete(eq("test-cache-key"))).thenReturn(true);

        given()
                .contentType(ContentType.JSON)
                .when().delete("/ai-review/cache/test-cache-key")
                .then()
                .statusCode(204);

        verify(geminiCacheService).delete(eq("test-cache-key"));
    }

    @Test
    void deleteCacheEntry_asAdmin_missingKey_returns404() {
        when(claimsContext.isAdmin()).thenReturn(true);
        when(geminiCacheService.delete(anyString())).thenReturn(false);

        given()
                .contentType(ContentType.JSON)
                .when().delete("/ai-review/cache/no-such-key")
                .then()
                .statusCode(404);
    }

    @Test
    void deleteCacheEntry_asManager_returns403() {
        // Admin-only: managers get 403 even though they have access to most
        // /ai-review/manager/* endpoints.
        when(claimsContext.isManager()).thenReturn(true);

        given()
                .contentType(ContentType.JSON)
                .when().delete("/ai-review/cache/anything")
                .then()
                .statusCode(403);

        verify(geminiCacheService, never()).delete(anyString());
    }

    @Test
    void deleteCacheEntry_asEmployee_returns403() {
        given()
                .contentType(ContentType.JSON)
                .when().delete("/ai-review/cache/anything")
                .then()
                .statusCode(403);

        verify(geminiCacheService, never()).delete(anyString());
    }

    @Test
    void deleteCacheEntry_serviceDdbFailure_returns500() {
        // GeminiCacheService.delete is contracted to throw IllegalStateException
        // on DDB get/delete failure (so operators see the real error). The
        // resource translates this to 500 with a typed MessageResponse.
        when(claimsContext.isAdmin()).thenReturn(true);
        when(geminiCacheService.delete(anyString()))
                .thenThrow(new IllegalStateException("DDB delete outage"));

        given()
                .contentType(ContentType.JSON)
                .when().delete("/ai-review/cache/anything")
                .then()
                .statusCode(500)
                .body("message", equalTo("Failed to delete cache entry"));
    }
}

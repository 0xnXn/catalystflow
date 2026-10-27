package com.catalystflow.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link ClaimsContext#populate}. No Quarkus bootstrap —
 * ObjectMapper is wired directly via reflection so we can exercise every
 * branch of the JWT decode logic without an HTTP round-trip.
 */
class ClaimsContextTest {

    private ClaimsContext claims;

    @BeforeEach
    void setUp() throws Exception {
        claims = new ClaimsContext();
        Field om = ClaimsContext.class.getDeclaredField("objectMapper");
        om.setAccessible(true);
        om.set(claims, new ObjectMapper());
    }

    // ── token builder ────────────────────────────────────────────────────────

    private static String base64url(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String token(String userId, String groupsJson) {
        String header  = base64url("{\"alg\":\"RS256\"}");
        String payload = base64url(
                "{"
                + "\"sub\":\"" + userId + "\","
                + "\"cognito:groups\":" + groupsJson
                + "}"
        );
        return "Bearer " + header + "." + payload + ".signature";
    }

    // ── empty/missing/malformed inputs ───────────────────────────────────────

    @Test
    void populate_nullHeader_leavesClaimsEmpty() {
        claims.populate(null);
        assertEquals("", claims.getUserId());
        assertFalse(claims.isAdmin());
        assertFalse(claims.isManager());
    }

    @Test
    void populate_nonBearerHeader_leavesClaimsEmpty() {
        claims.populate("Basic abc");
        assertEquals("", claims.getUserId());
        assertFalse(claims.isAdmin());
        assertFalse(claims.isManager());
    }

    @Test
    void populate_tokenWithFewerThanTwoParts_leavesClaimsEmpty() {
        claims.populate("Bearer onlyonepart");
        assertEquals("", claims.getUserId());
    }

    @Test
    void populate_unparseablePayload_swallowsExceptionAndLeavesClaimsEmpty() {
        // Valid base64url but not JSON
        String bogus = "Bearer header." + base64url("not-json-at-all") + ".sig";
        claims.populate(bogus);
        assertEquals("", claims.getUserId());
        assertFalse(claims.isAdmin());
    }

    // ── valid token branches ────────────────────────────────────────────────

    @Test
    void populate_adminGroup_setsAdminTrue() {
        claims.populate(token("admin-1", "[\"ADMIN\"]"));
        assertEquals("admin-1", claims.getUserId());
        assertTrue(claims.isAdmin());
        assertFalse(claims.isManager());
    }

    @Test
    void populate_managerGroup_setsManagerTrue() {
        claims.populate(token("mgr-1", "[\"MANAGER\"]"));
        assertEquals("mgr-1", claims.getUserId());
        assertFalse(claims.isAdmin());
        assertTrue(claims.isManager());
    }

    @Test
    void populate_employeeGroup_neitherFlag() {
        claims.populate(token("emp-1", "[\"EMPLOYEE\"]"));
        assertEquals("emp-1", claims.getUserId());
        assertFalse(claims.isAdmin());
        assertFalse(claims.isManager());
    }

    @Test
    void populate_multipleGroups_setsBoth() {
        claims.populate(token("super", "[\"ADMIN\",\"MANAGER\"]"));
        assertTrue(claims.isAdmin());
        assertTrue(claims.isManager());
    }

    @Test
    void populate_groupsAsString_fallbackParser() {
        // Cognito occasionally serialises groups as a comma-string
        // (e.g. when the claim is forwarded via API Gateway with custom mapping)
        claims.populate(token("admin-string", "\"ADMIN\""));
        assertTrue(claims.isAdmin());
    }

    @Test
    void populate_payloadNeedsBase64Padding_stillDecodes() {
        // Construct a payload whose length % 4 != 0 to exercise the padding branch
        String payload = base64url("{\"sub\":\"u\"}"); // length not multiple of 4
        String bearer = "Bearer header." + payload + ".sig";
        claims.populate(bearer);
        assertEquals("u", claims.getUserId());
    }

    @Test
    void populate_missingSubClaim_leavesUserIdEmpty() {
        String payload = base64url("{\"cognito:groups\":[\"EMPLOYEE\"]}");
        String bearer = "Bearer header." + payload + ".sig";
        claims.populate(bearer);
        assertEquals("", claims.getUserId());
        assertFalse(claims.isAdmin());
    }
}

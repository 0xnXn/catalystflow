package com.catalystflow.user.integration;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Builds crafted JWTs for integration tests.
 *
 * Why this works without real Cognito:
 *   ClaimsContext.populate() only base64url-decodes the middle section (payload)
 *   of the JWT — it never verifies the signature. API Gateway does that in
 *   production. In tests we skip API Gateway entirely and hit the Quarkus
 *   server directly, so we can craft any payload we need.
 *
 * Format: base64url(header) . base64url(payload) . fakesignature
 */
public class TestJwtHelper {

    private TestJwtHelper() {}

    // ── Token factories ───────────────────────────────────────────────────────

    public static String adminToken(String userId) {
        return buildToken(userId, "ADMIN");
    }

    public static String managerToken(String userId) {
        return buildToken(userId, "MANAGER");
    }

    public static String employeeToken(String userId) {
        return buildToken(userId, "EMPLOYEE");
    }

    // ── Internal builder ──────────────────────────────────────────────────────

    private static String buildToken(String userId, String group) {
        String header  = base64url("{\"alg\":\"RS256\",\"kid\":\"test\"}");

        // This is the only part ClaimsContext reads — sub and cognito:groups
        String payload = base64url(
            "{"
            + "\"sub\":\"" + userId + "\","
            + "\"cognito:groups\":[\"" + group + "\"],"
            + "\"exp\":9999999999"
            + "}"
        );

        // Signature is intentionally fake — ClaimsContext never verifies it
        return header + "." + payload + ".fakesignature";
    }

    private static String base64url(String json) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}

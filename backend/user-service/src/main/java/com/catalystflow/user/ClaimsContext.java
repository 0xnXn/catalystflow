package com.catalystflow.user;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Request-scoped bean that holds the decoded Cognito JWT claims for the
 * current inbound request.
 *
 * <p>Populated by {@link AuthFilter} before any resource method runs.
 * API Gateway has already verified the JWT signature; we only base64url-decode
 * the payload to read the claims — no re-verification needed.
 *
 * <p>Mocked in tests via {@code @InjectMock ClaimsContext} so tests can
 * simulate any user identity without needing a real JWT.
 */
@RequestScoped
public class ClaimsContext {

    @Inject
    ObjectMapper objectMapper;

    private static final boolean AUTH_DISABLED =
            "true".equalsIgnoreCase(System.getenv("AUTH_DISABLED"));

    private String userId = "";
    private boolean admin = false;
    private boolean manager = false;

    /**
     * Decodes the JWT payload and extracts {@code sub} and {@code cognito:groups}.
     * Called once per request by {@link AuthFilter}.
     */
    public void populate(String authorizationHeader) {
        if (AUTH_DISABLED) {
            userId  = "local-dev-user";
            admin   = true;
            manager = true;
            return;
        }
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) return;
        try {
            String token = authorizationHeader.substring(7);
            String[] parts = token.split("\\.");
            if (parts.length < 2) return;

            // Pad to a valid Base64 length before decoding
            String encoded = parts[1];
            encoded += "=".repeat((4 - encoded.length() % 4) % 4);
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);

            Map<String, Object> claims = objectMapper.readValue(decoded, new TypeReference<>() {});

            userId = Objects.toString(claims.get("sub"), "");

            Object groups = claims.get("cognito:groups");
            if (groups instanceof List<?> groupList) {
                admin   = groupList.contains("ADMIN");
                manager = groupList.contains("MANAGER");
            } else {
                String groupStr = Objects.toString(groups, "");
                admin   = groupStr.contains("ADMIN");
                manager = groupStr.contains("MANAGER");
            }
        } catch (Exception ignored) {
            // Malformed token — treat as unauthenticated
        }
    }

    /** Returns the Cognito {@code sub} (unique user ID), or empty string if not authenticated. */
    public String getUserId() {
        return userId;
    }

    /** Returns {@code true} if the caller belongs to the ADMIN Cognito group. */
    public boolean isAdmin() {
        return admin;
    }

    /** Returns {@code true} if the caller belongs to the MANAGER Cognito group. */
    public boolean isManager() {
        return manager;
    }
}

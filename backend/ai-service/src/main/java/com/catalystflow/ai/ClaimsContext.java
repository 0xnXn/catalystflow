package com.catalystflow.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Request-scoped bean that holds the Cognito JWT claims for the current HTTP request.
 *
 * <p>Per-service duplication of the same pattern in user-service and review-service.
 */
@RequestScoped
public class ClaimsContext {

    @Inject
    ObjectMapper objectMapper;

    private String userId = "";
    private boolean admin = false;
    private boolean manager = false;

    public void populate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) return;
        try {
            String token = authorizationHeader.substring(7);
            String[] parts = token.split("\\.");
            if (parts.length < 2) return;

            String encoded = parts[1];
            encoded += "=".repeat((4 - encoded.length() % 4) % 4);
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);

            Map<String, Object> claims = objectMapper.readValue(
                    decoded, new TypeReference<Map<String, Object>>() {});

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
            // Malformed token — claims stay empty
        }
    }

    public String getUserId() { return userId; }

    public boolean isAdmin() { return admin; }

    public boolean isManager() { return manager; }
}

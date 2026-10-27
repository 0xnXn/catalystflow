package com.catalystflow.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RequestScoped
public class ClaimsContext {

    private static final Logger LOG = Logger.getLogger(ClaimsContext.class);

    @Inject
    ObjectMapper objectMapper;

    private String userId = "";
    private boolean admin;
    private boolean manager;

    public void populate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) return;
        try {
            String token = authorizationHeader.substring(7);
            String[] parts = token.split("\\.");
            if (parts.length < 2) return;

            String encoded = parts[1];
            encoded += "=".repeat((4 - encoded.length() % 4) % 4);
            Map<String, Object> claims = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(encoded), new TypeReference<Map<String, Object>>() {});

            userId = Objects.toString(claims.get("sub"), "");
            Object groups = claims.get("cognito:groups");
            if (groups instanceof List<?> list) {
                admin = list.contains("ADMIN");
                manager = list.contains("MANAGER");
            } else {
                String groupString = Objects.toString(groups, "");
                admin = groupString.contains("ADMIN");
                manager = groupString.contains("MANAGER");
            }
        } catch (Exception e) {
            LOG.warnf(e, "JWT parse error; notification claims left empty");
        }
    }

    public String getUserId() { return userId; }
    public boolean isAdmin() { return admin; }
    public boolean isManager() { return manager; }
}

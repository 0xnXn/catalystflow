package com.catalystflow.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Request-scoped bean that holds the Cognito JWT claims for the current HTTP request.
 *
 * <p>Populated by {@link AuthFilter} on every inbound request. The JWT is decoded
 * (not re-verified — API Gateway already verified the signature) to extract the
 * {@code sub} (userId) and {@code cognito:groups} claims.
 *
 * <p>In tests, this bean is replaced by an {@code @InjectMock} so tests can
 * set any user identity without needing a real JWT.
 */
@RequestScoped
public class ClaimsContext {

    private static final Logger LOG = Logger.getLogger(ClaimsContext.class);

    @Inject
    ObjectMapper objectMapper;

    private String userId = "";
    private boolean admin = false;
    private boolean manager = false;

    /**
     * Decodes the JWT payload and extracts Cognito claims.
     * Called once per request by {@link AuthFilter}.
     */
    public void populate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) return;
        try {
            String token = authorizationHeader.substring(7);
            String[] parts = token.split("\\.");
            if (parts.length < 2) return;

            // JWT payload is base64url-encoded — add padding and decode
            String encoded = parts[1];
            encoded += "=".repeat((4 - encoded.length() % 4) % 4);
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);

            Map<String, Object> claims = objectMapper.readValue(
                    decoded, new TypeReference<Map<String, Object>>() {});

            userId = Objects.toString(claims.get("sub"), "");

            // Cognito puts groups as an array in the JWT: ["ADMIN"], ["MANAGER"] or ["EMPLOYEE"]
            Object groups = claims.get("cognito:groups");
            if (groups instanceof List<?> groupList) {
                admin   = groupList.contains("ADMIN");
                manager = groupList.contains("MANAGER");
            } else {
                String groupStr = Objects.toString(groups, "");
                admin   = groupStr.contains("ADMIN");
                manager = groupStr.contains("MANAGER");
            }
        } catch (Exception e) {
            // Malformed token — claims stay empty. API Gateway should have rejected it,
            // so reaching here means a config drift or a bug worth surfacing in CloudWatch.
            LOG.warnf(e, "JWT parse error; claims left empty");
        }
    }

    public String getUserId() { return userId; }

    public boolean isAdmin() { return admin; }

    public boolean isManager() { return manager; }
}

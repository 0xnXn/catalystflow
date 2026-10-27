package com.catalystflow.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimsContextTest {

    @Test
    void populate_withListGroups_setsUserAndRoles() throws Exception {
        ClaimsContext ctx = new ClaimsContext();
        setField(ctx, "objectMapper", new ObjectMapper());

        String payload = "{\"sub\":\"user-1\",\"cognito:groups\":[\"ADMIN\",\"MANAGER\"]}";
        ctx.populate("Bearer " + jwtWithPayload(payload));

        assertEquals("user-1", ctx.getUserId());
        assertTrue(ctx.isAdmin());
        assertTrue(ctx.isManager());
    }

    @Test
    void populate_withStringGroups_setsRoles() throws Exception {
        ClaimsContext ctx = new ClaimsContext();
        setField(ctx, "objectMapper", new ObjectMapper());

        String payload = "{\"sub\":\"user-2\",\"cognito:groups\":\"ADMIN,MANAGER\"}";
        ctx.populate("Bearer " + jwtWithPayload(payload));

        assertEquals("user-2", ctx.getUserId());
        assertTrue(ctx.isAdmin());
        assertTrue(ctx.isManager());
    }

    @Test
    void populate_invalidHeader_keepsDefaults() throws Exception {
        ClaimsContext ctx = new ClaimsContext();
        setField(ctx, "objectMapper", new ObjectMapper());

        ctx.populate("Basic abc");

        assertEquals("", ctx.getUserId());
        assertFalse(ctx.isAdmin());
        assertFalse(ctx.isManager());
    }

    private static String jwtWithPayload(String payloadJson) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}

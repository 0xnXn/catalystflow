package com.catalystflow.ai;

import com.catalystflow.ai.model.MessageResponse;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BaseResourceTest {

    static class TestResource extends BaseResource {}

    @Test
    void helpers_and_claimDelegation_work() throws Exception {
        TestResource resource = new TestResource();
        ClaimsContext claims = mock(ClaimsContext.class);
        when(claims.getUserId()).thenReturn("u1");
        when(claims.isAdmin()).thenReturn(true);
        when(claims.isManager()).thenReturn(false);
        setField(resource, "claimsContext", claims);

        assertEquals("u1", resource.getUserId());
        assertEquals(true, resource.isAdmin());
        assertEquals(false, resource.isManager());

        Response bad = resource.badRequest("bad");
        assertEquals(400, bad.getStatus());
        assertEquals("bad", ((MessageResponse) bad.getEntity()).getMessage());

        Response notFound = resource.notFound("missing");
        assertEquals(404, notFound.getStatus());
        assertEquals("missing", ((MessageResponse) notFound.getEntity()).getMessage());

        Response forbidden = resource.forbidden();
        assertEquals(403, forbidden.getStatus());
        assertEquals("Forbidden", ((MessageResponse) forbidden.getEntity()).getMessage());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = BaseResource.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}

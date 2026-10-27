package com.catalystflow.ai;

import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthFilterTest {

    @Test
    void filter_populatesClaimsFromAuthorizationHeader() throws Exception {
        AuthFilter filter = new AuthFilter();
        ClaimsContext claimsContext = mock(ClaimsContext.class);
        setField(filter, "claimsContext", claimsContext);

        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getHeaderString("Authorization")).thenReturn("Bearer token");

        filter.filter(ctx);

        verify(claimsContext).populate("Bearer token");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}

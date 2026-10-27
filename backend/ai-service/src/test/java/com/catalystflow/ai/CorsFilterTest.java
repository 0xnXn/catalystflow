package com.catalystflow.ai;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CorsFilterTest {

    @Test
    void filter_setsCorsHeaders() throws Exception {
        CorsFilter filter = new CorsFilter();
        ContainerRequestContext req = mock(ContainerRequestContext.class);
        ContainerResponseContext res = mock(ContainerResponseContext.class);
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        when(res.getHeaders()).thenReturn(headers);

        filter.filter(req, res);

        assertEquals("*", headers.getFirst("Access-Control-Allow-Origin"));
        assertEquals("GET,POST,PUT,DELETE,OPTIONS", headers.getFirst("Access-Control-Allow-Methods"));
        assertEquals("Content-Type,Authorization", headers.getFirst("Access-Control-Allow-Headers"));
    }
}

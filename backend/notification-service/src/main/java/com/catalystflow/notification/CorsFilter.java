package com.catalystflow.notification;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * JAX-RS filter that appends CORS headers to every outbound response.
 *
 * <p>API Gateway also sets CORS headers (via the {@code Cors:} block in template.yaml),
 * but this filter ensures they are present even for direct Lambda invocations
 * and during local development / testing.
 */
@Provider
@ApplicationScoped
public class CorsFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        response.getHeaders().putSingle("Access-Control-Allow-Origin",  "*");
        response.getHeaders().putSingle("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        response.getHeaders().putSingle("Access-Control-Allow-Headers", "Content-Type,Authorization");
    }
}

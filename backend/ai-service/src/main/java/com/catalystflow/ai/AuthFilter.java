package com.catalystflow.ai;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * JAX-RS filter that runs on every inbound request and populates {@link ClaimsContext}
 * with the Cognito JWT claims decoded from the Authorization header.
 *
 * <p>Per-service duplication of the same pattern in user-service and review-service
 * — see project memory: shared models / filters refactor is deferred.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION)
public class AuthFilter implements ContainerRequestFilter {

    @Inject
    ClaimsContext claimsContext;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        claimsContext.populate(requestContext.getHeaderString("Authorization"));
    }
}

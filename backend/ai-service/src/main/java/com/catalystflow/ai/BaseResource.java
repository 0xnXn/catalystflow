package com.catalystflow.ai;

import com.catalystflow.ai.model.MessageResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * Shared base class for all JAX-RS resources in the ai-service.
 * Per-service duplication of the same pattern in user-service / review-service.
 */
public abstract class BaseResource {

    @Inject
    ClaimsContext claimsContext;

    protected boolean isAdmin() {
        return claimsContext.isAdmin();
    }

    protected boolean isManager() {
        return claimsContext.isManager();
    }

    protected String getUserId() {
        return claimsContext.getUserId();
    }

    protected Response forbidden() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(new MessageResponse().message("Forbidden"))
                .build();
    }

    protected Response notFound(String message) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(new MessageResponse().message(message))
                .build();
    }

    protected Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new MessageResponse().message(message))
                .build();
    }
}

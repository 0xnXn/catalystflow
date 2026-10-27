package com.catalystflow.user;

import com.catalystflow.user.model.MessageResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/**
 * Shared base class for all JAX-RS resources in the user-service.
 *
 * <p>Provides:
 * <ul>
 *   <li>Auth helpers — {@link #isAdmin()} and {@link #getUserId()} via {@link ClaimsContext}</li>
 *   <li>Response factories — {@link #forbidden()}, {@link #notFound}, {@link #badRequest}</li>
 * </ul>
 *
 * <p>Keeping these here eliminates duplication and gives every resource a single
 * place to look for auth and response-building logic.
 */
public abstract class BaseResource {

    @Inject
    ClaimsContext claimsContext;

    // ── Auth helpers ──────────────────────────────────────────────────────────

    /** Returns true if the current caller belongs to the ADMIN Cognito group. */
    protected boolean isAdmin() {
        return claimsContext.isAdmin();
    }

    /** Returns true if the current caller belongs to the MANAGER Cognito group. */
    protected boolean isManager() {
        return claimsContext.isManager();
    }

    /** Returns the Cognito {@code sub} (unique user ID) of the current caller. */
    protected String getUserId() {
        return claimsContext.getUserId();
    }

    // ── Response factories ────────────────────────────────────────────────────

    protected Response forbidden() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(new MessageResponse().message("Forbidden: ADMIN role required"))
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

    protected Response conflict(String message) {
        return Response.status(Response.Status.CONFLICT)
                .entity(new MessageResponse().message(message))
                .build();
    }

    protected Response serverError(String message) {
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new MessageResponse().message(message))
                .build();
    }
}

package com.catalystflow.notification;

import com.catalystflow.notification.model.MessageResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

public abstract class BaseResource {

    @Inject
    ClaimsContext claimsContext;

    protected boolean isAdmin() { return claimsContext.isAdmin(); }
    protected boolean isManager() { return claimsContext.isManager(); }
    protected String getUserId() { return claimsContext.getUserId(); }

    protected Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new MessageResponse().message(message))
                .build();
    }

    protected Response forbidden(String message) {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(new MessageResponse().message(message))
                .build();
    }

    protected Response notFound(String message) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(new MessageResponse().message(message))
                .build();
    }
}

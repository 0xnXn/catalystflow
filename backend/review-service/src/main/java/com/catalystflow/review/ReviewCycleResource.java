package com.catalystflow.review;

import com.catalystflow.review.api.ReviewCyclesApi;
import com.catalystflow.review.model.CreateReviewCycleRequest;
import com.catalystflow.review.model.MessageResponse;
import com.catalystflow.review.model.ReviewCycle;
import com.catalystflow.review.model.UpdateReviewCycleRequest;
import com.catalystflow.review.service.NotificationIntentPublisher;
import com.catalystflow.review.service.ReviewCycleService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Path("/cycles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReviewCycleResource extends BaseResource implements ReviewCyclesApi {

    private static final Logger LOG = Logger.getLogger(ReviewCycleResource.class);
    private static final ZoneId AU_ZONE = ZoneId.of("Australia/Sydney");

    @Inject
    ReviewCycleService reviewCycleService;

    @Inject
    NotificationIntentPublisher notificationIntentPublisher;

    @Override
    @GET
    @Path("/health")
    public Response getCycleHealth() {
        return Response.ok(new MessageResponse().message("Review Cycle Service is Online")).build();
    }

    @Override
    @POST
    @Path("/sync")
    public Response syncReviewCycles() {
        try {
            LocalDate todayAu = ZonedDateTime.now(AU_ZONE).toLocalDate();
            reviewCycleService.syncCycleStatuses(todayAu);
            notificationIntentPublisher.publishAutoDeadlineScan(todayAu);
            return Response.ok(new MessageResponse().message("Sync complete for date: " + todayAu)).build();
        } catch (Exception e) {
            LOG.error("Failed to sync review cycles", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new MessageResponse().message("Sync failed: " + e.getMessage()))
                    .build();
        }
    }

    @Override
    @POST
    public Response createReviewCycle(CreateReviewCycleRequest request) {
        if (!isAdmin()) return forbidden();
        try {
            ReviewCycle created = reviewCycleService.createCycle(toCycle(request));
            return Response.status(Response.Status.CREATED).entity(created).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new MessageResponse().message(e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.error("Failed to create review cycle", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new MessageResponse().message(e.getClass().getSimpleName() + ": " + e.getMessage()))
                    .build();
        }
    }

    @Override
    @POST
    @Path("/{cycleId}/publish")
    public Response publishReviewCycle(@PathParam("cycleId") String cycleId) {
        if (!isAdmin()) return forbidden();
        try {
            return reviewCycleService.publishCycle(cycleId)
                    .map(cycle -> Response.ok(cycle).build())
                    .orElse(Response.status(Response.Status.NOT_FOUND).build());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new MessageResponse().message(e.getMessage()))
                    .build();
        }
    }

    @Override
    @POST
    @Path("/{cycleId}/activate")
    public Response activateReviewCycle(@PathParam("cycleId") String cycleId) {
        if (!isAdmin()) return forbidden();
        try {
            return reviewCycleService.activateCycle(cycleId)
                    .map(cycle -> Response.ok(cycle).build())
                    .orElse(Response.status(Response.Status.NOT_FOUND).build());
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new MessageResponse().message(e.getMessage()))
                    .build();
        }
    }

    @Override
    @POST
    @Path("/{cycleId}/complete")
    public Response completeReviewCycle(@PathParam("cycleId") String cycleId) {
        if (!isAdmin()) return forbidden();
        try {
            return reviewCycleService.completeCycle(cycleId)
                    .map(cycle -> Response.ok(cycle).build())
                    .orElse(Response.status(Response.Status.NOT_FOUND).build());
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new MessageResponse().message(e.getMessage()))
                    .build();
        }
    }

    @Override
    @GET
    public Response listReviewCycles(@QueryParam("status") String status) {
        try {
            var cycles = reviewCycleService.getAllCycles(status);
            stripIdealResponses(cycles);
            return Response.ok(cycles).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new MessageResponse().message(e.getMessage()))
                    .build();
        }
    }

    @Override
    @GET
    @Path("/{cycleId}")
    public Response getReviewCycle(@PathParam("cycleId") String cycleId) {
        return reviewCycleService.getReviewCycle(cycleId)
                .map(cycle -> {
                    stripIdealResponses(cycle);
                    return Response.ok(cycle).build();
                })
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    @Override
    @PUT
    @Path("/{cycleId}")
    public Response updateReviewCycle(
            @PathParam("cycleId") String cycleId,
            UpdateReviewCycleRequest request
    ) {
        if (!isAdmin()) return forbidden();
        try {
            return reviewCycleService.updateCycle(cycleId, toCycle(request))
                    .map(cycle -> Response.ok(cycle).build())
                    .orElse(Response.status(Response.Status.NOT_FOUND).build());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new MessageResponse().message(e.getMessage()))
                    .build();
        }
    }

    @Override
    @DELETE
    @Path("/{cycleId}")
    public Response deleteReviewCycle(@PathParam("cycleId") String cycleId) {
        if (!isAdmin()) return forbidden();
        boolean deleted = reviewCycleService.deleteCycle(cycleId);
        return deleted ? Response.noContent().build() : Response.status(Response.Status.NOT_FOUND).build();
    }

    private ReviewCycle toCycle(CreateReviewCycleRequest request) {
        ReviewCycle cycle = new ReviewCycle();
        if (request == null) return cycle;
        cycle.setCycleTitle(request.getCycleTitle());
        cycle.setTemplateId(request.getTemplateId());
        cycle.setStartDate(request.getStartDate());
        cycle.setDueDate(request.getDueDate());
        cycle.setMilestones(request.getMilestones());
        return cycle;
    }

    private ReviewCycle toCycle(UpdateReviewCycleRequest request) {
        ReviewCycle cycle = new ReviewCycle();
        if (request == null) return cycle;
        cycle.setCycleTitle(request.getCycleTitle());
        cycle.setTemplateId(request.getTemplateId());
        cycle.setStartDate(request.getStartDate());
        cycle.setDueDate(request.getDueDate());
        if (request.getStatus() != null) {
            cycle.setStatus(ReviewCycle.StatusEnum.fromValue(request.getStatus().value()));
        }
        cycle.setMilestones(request.getMilestones());
        return cycle;
    }
}

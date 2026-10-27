package com.catalystflow.review;

import com.catalystflow.review.api.Peer360AssignmentsApi;
import com.catalystflow.review.model.CreatePeer360AssignmentsRequest;
import com.catalystflow.review.model.MessageResponse;
import com.catalystflow.review.model.Peer360AssignmentResult;
import com.catalystflow.review.model.Review;
import com.catalystflow.review.service.Peer360AssignmentService;
import com.catalystflow.review.service.Peer360AssignmentService.DeleteOutcome;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Manager-driven 360 assignment endpoints.
 *
 * Routes:
 *   POST   /cycles/{cycleId}/peer360-assignments              — create batch (idempotent)
 *   GET    /cycles/{cycleId}/peer360-assignments?subjectUserId=...
 *                                                              — list (manager / admin only;
 *                                                                subject themselves cannot read)
 *   DELETE /cycles/{cycleId}/peer360-assignments/{reviewId}   — remove if NOT_STARTED
 *
 * Authentication: JWT decoded by {@link AuthFilter} into {@link ClaimsContext}.
 */
@Path("/cycles/{cycleId}/peer360-assignments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class Peer360AssignmentResource extends BaseResource implements Peer360AssignmentsApi {

    private static final Logger LOG = Logger.getLogger(Peer360AssignmentResource.class);

    @Inject
    Peer360AssignmentService assignmentService;

    @POST
    @Override
    public Response createPeer360Assignments(@PathParam("cycleId") String cycleId,
                                             CreatePeer360AssignmentsRequest request) {
        if (cycleId == null || cycleId.isBlank()) {
            return badRequest("cycleId is required");
        }
        // Validate the subject is present before authorizing against it: a malformed
        // body is a 400, not a misleading 403, and the authz below needs a non-blank
        // subjectUserId. (Service re-validates the rest of the body.)
        if (request == null || request.getSubjectUserId() == null || request.getSubjectUserId().isBlank()) {
            return badRequest("'subjectUserId' is required");
        }
        String subjectUserId = request.getSubjectUserId();
        // Object-level authz: only the subject's direct manager (or admin) may
        // create peer-360 assignments — not any manager-role user (RVT-04).
        if (!isAdmin() && !isSubjectsManager(getUserId(), subjectUserId)) {
            return forbidden("Forbidden: you are not the manager of this employee");
        }

        try {
            Peer360AssignmentResult result = assignmentService.createAssignments(
                    cycleId, request, getUserId());
            LOG.infof("Peer360 assignments created by %s for cycle=%s subject=%s (created=%d skipped=%d)",
                    getUserId(),
                    cycleId,
                    request == null ? "?" : request.getSubjectUserId(),
                    result.getCreated() == null ? 0 : result.getCreated().size(),
                    result.getSkipped() == null ? 0 : result.getSkipped().size());
            return Response.ok(result).build();
        } catch (BadRequestException e) {
            return badRequest(e.getMessage());
        }
    }

    @GET
    @Override
    public Response listPeer360Assignments(@PathParam("cycleId") String cycleId,
                                           @QueryParam("subjectUserId") String subjectUserId) {
        if (cycleId == null || cycleId.isBlank() || subjectUserId == null || subjectUserId.isBlank()) {
            return badRequest("cycleId and subjectUserId are required");
        }
        // Anonymity: subject themselves cannot list peer assignments.
        if (subjectUserId.equals(getUserId())) return forbidden();
        // Object-level authz: only the subject's direct manager (or admin) — not
        // any manager-role user — may read another employee's peer-360 rows (RVT-04).
        if (!isAdmin() && !isSubjectsManager(getUserId(), subjectUserId)) {
            return forbidden("Forbidden: you are not the manager of this employee");
        }

        // Do NOT strip answers. This endpoint is admin/manager-only and forbids
        // the subject (above), so the caller is authorized to read peer
        // feedback content. The manager review form renders these answers in
        // its "360 Raw" tab and uses them for citation highlighting —
        // stripping emptied the manager's peer-feedback view.
        List<Review> rows = assignmentService.listAssignments(cycleId, subjectUserId);
        return Response.ok(rows).build();
    }

    @DELETE
    @Path("/{reviewId}")
    @Override
    public Response deletePeer360Assignment(@PathParam("cycleId") String cycleId,
                                            @PathParam("reviewId") String reviewId) {
        if (reviewId == null || reviewId.isBlank()) {
            return badRequest("reviewId is required");
        }
        if (!isAdmin() && !isManager()) return forbidden();

        // Cycle scoping: the assignment must belong to the cycle in the path.
        Optional<Review> existing = assignmentService.getAssignment(reviewId);
        if (existing.isEmpty()) return notFound("Peer 360 assignment not found: " + reviewId);
        if (cycleId != null && !cycleId.equals(existing.get().getCycleId())) {
            return notFound("Peer 360 assignment not found in cycle: " + cycleId);
        }
        // Object-level authz: the caller must manage the subject this assignment
        // belongs to (admins bypass). Resolved from the row — the subject is not
        // in the path (RVT-04).
        if (!isAdmin() && !isSubjectsManager(getUserId(), existing.get().getSubjectUserId())) {
            return forbidden("Forbidden: you are not the manager of this employee");
        }

        DeleteOutcome outcome = assignmentService.deleteAssignment(reviewId);
        return switch (outcome) {
            case DELETED -> Response.noContent().build();
            case NOT_FOUND -> notFound("Peer 360 assignment not found: " + reviewId);
            case IN_PROGRESS -> Response.status(Response.Status.CONFLICT)
                    .entity(new MessageResponse().message(
                            "Cannot delete: peer has already started this review (use waive flow once available)"))
                    .build();
        };
    }
}

package com.catalystflow.review;

import com.catalystflow.review.api.ReviewsApi;
import com.catalystflow.review.model.*;
import com.catalystflow.review.service.ReviewCycleService;
import com.catalystflow.review.service.ReviewService;
import com.catalystflow.review.service.NotificationIntentPublisher;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JAX-RS resource for reviews.
 *
 * Endpoints:
 *   GET    /reviews/health              — health check (no auth required)
 *   GET    /reviews/cycle/{cycleId}     — all reviews in a cycle (admin/manager)
 *   GET    /reviews/my-review          — reviews where caller is the subject
 *   GET    /reviews/assigned-to-me      — reviews caller must fill out
 *   POST   /reviews                     — create a review (admin)
 *   GET    /reviews/{reviewId}          — get single review
 *   PUT    /reviews/{reviewId}          — update answers / status
 *   DELETE /reviews/{reviewId}          — delete review (admin)
 *
 * Authentication: JWT decoded by {@link AuthFilter} into {@link ClaimsContext}.
 */
@Path("/reviews")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReviewResource extends BaseResource implements ReviewsApi {

    private static final Logger LOG = Logger.getLogger(ReviewResource.class);

    @Inject
    ReviewService reviewService;

    @Inject
    ReviewCycleService cycleService;

    @Inject
    NotificationIntentPublisher notificationIntentPublisher;

    // ── Health ────────────────────────────────────────────────────────────────

    @GET
    @Path("/health")
    @Override
    public Response getReviewHealth() {
        return Response.ok(new MessageResponse().message("Review Service is Online")).build();
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    /**
     * Flexible search — admin / manager only.
     *
     * Query params (at least one required):
     *   subjectUserId — filter by employee being reviewed
     *   cycleId       — filter by review cycle
     *
     * Examples:
     *   GET /reviews?subjectUserId=alice                    all reviews for alice
     *   GET /reviews?cycleId=cycle-Q1                      all reviews in cycle
     *   GET /reviews?subjectUserId=alice&cycleId=cycle-Q1  alice's reviews in that cycle
     */
    @GET
    @Override
    public Response listReviews(
            @QueryParam("subjectUserId") String subjectUserId,
            @QueryParam("cycleId")       String cycleId) {

        if (!isAdmin() && !isManager()) return forbidden();

        List<Review> reviews;

        boolean hasSubject = subjectUserId != null;
        boolean hasCycle   = cycleId != null;

        // A non-admin manager may only pull reviews for their own direct reports.
        // Without this, any manager could read any employee's full reviews
        // (answers included) via ?subjectUserId=.
        if (hasSubject && !isAdmin() && !isSubjectsManager(getUserId(), subjectUserId)) {
            return forbidden();
        }

        if (hasSubject && hasCycle) {
            // Both — query by subject then filter by cycle in memory
            reviews = reviewService.getReviewsForSubject(subjectUserId)
                    .stream()
                    .filter(r -> cycleId.equals(r.getCycleId()))
                    .toList();
            // overallStatus is meaningful: all reviews for this one person in this cycle
            return ok(reviews);
        } else if (hasSubject) {
            reviews = reviewService.getReviewsForSubject(subjectUserId);
            // overallStatus across all cycles for this person — still useful
            return ok(reviews);
        } else if (hasCycle) {
            reviews = reviewService.getReviewsForCycle(cycleId);
            stripAnswers(reviews);
            // cycleId-only: reviews span multiple subjects — no single overallStatus
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reviews", reviews);
            return Response.ok(body).build();
        } else {
            return badRequest("Provide at least one query param: subjectUserId or cycleId");
        }
    }

    /** All reviews for a cycle — admin / manager only. */
    @GET
    @Path("/cycle/{cycleId}")
    @Override
    public Response getReviewsByCycle(@PathParam("cycleId") String cycleId) {
        if (!isAdmin() && !isManager()) return forbidden();
        List<Review> reviews = reviewService.getReviewsForCycle(cycleId);
        stripAnswers(reviews); // cycle-keyed overview — status/ids only, no answer bodies
        return ok(reviews);
    }

    /**
     * Reviews where the caller is the subject (their own performance reviews).
     * PEER_360 rows are intentionally hidden from the subject — peer feedback is
     * anonymous to the subject and only readable by their manager / admins.
     */
    @GET
    @Path("/my-review")
    @Override
    public Response getMyReviews() {
        String userId = getUserId();
        if (userId.isEmpty()) return unauthorized();
        List<Review> reviews = reviewService.getReviewsForSubject(userId).stream()
                .filter(r -> r.getReviewType() != Review.ReviewTypeEnum.PEER_360)
                .toList();
        // Do NOT strip answers — this returns the caller's OWN SELF reviews and
        // the MANAGER reviews written about them. The employee reads the
        // published manager feedback content here (review history). Stripping
        // made published manager reviews show up empty in employee accounts.
        return Response.ok(Map.of("reviews", reviews)).build();
    }

    /**
     * Reviews the caller needs to fill out (they are the author).
     * PEER_360 rows are only surfaced once the parent cycle is ACTIVE — peers
     * cannot start filling assignments while the cycle is still SCHEDULED.
     */
    @GET
    @Path("/assigned-to-me")
    @Override
    public Response getReviewsAssignedToMe() {
        String userId = getUserId();
        if (userId.isEmpty()) return unauthorized();
        List<Review> reviews = reviewService.getReviewsForAuthor(userId).stream()
                .filter(this::isFillable)
                .toList();
        // Do NOT strip answers — the caller authored these and the review form
        // repopulates a half-finished draft from review.answers when they come
        // back to continue. Stripping would blank out saved progress on resume.
        return Response.ok(Map.of("reviews", reviews)).build();
    }

    /**
     * Get a single review by ID.
     * Anonymity rule: a non-admin/non-manager subject cannot read PEER_360 reviews
     * about themselves. Authors, admins, and managers always pass.
     */
    @GET
    @Path("/{reviewId}")
    @Override
    public Response getReview(@PathParam("reviewId") String reviewId) {
        Review review = reviewService.getReviewById(reviewId).orElse(null);
        if (review == null) return notFound("Review not found: " + reviewId);

        String callerId = getUserId();
        boolean isPeer360AboutCaller = review.getReviewType() == Review.ReviewTypeEnum.PEER_360
                && callerId.equals(review.getSubjectUserId());
        if (isPeer360AboutCaller && !isAdmin() && !isManager()) {
            return forbidden("Peer 360 feedback is not visible to the subject.");
        }

        // Ownership scoping (was missing): previously any authenticated user could
        // read any review by id. Limit reads to the author, the subject, the
        // subject's manager, or an admin. 404 (not 403) for everyone else so a
        // stranger can't probe which reviewIds exist.
        boolean allowed = isAdmin()
                || callerId.equals(review.getAuthorUserId())
                || callerId.equals(review.getSubjectUserId())
                || isSubjectsManager(callerId, review.getSubjectUserId());
        if (!allowed) return notFound("Review not found: " + reviewId);

        return Response.ok(review).build();
    }

    @GET
    @Path("/manager-gate")
    @Override
    public Response getManagerGate(
            @QueryParam("cycleId") String cycleId,
            @QueryParam("subjectUserId") String subjectUserId) {
        if (cycleId == null || cycleId.isBlank() || subjectUserId == null || subjectUserId.isBlank()) {
            return badRequest("cycleId and subjectUserId are required");
        }
        if (!isAdmin() && !isManager()) return forbidden();
        return Response.ok(reviewService.getManagerGateStatus(cycleId, subjectUserId)).build();
    }

    // ── Bundle publish ────────────────────────────────────────────────────────

    /**
     * Publishes all reviews for a (cycle, subject) in one go — admin / manager only.
     * Every review must be COMPLETED first; any non-completed row returns 400.
     *
     * POST /reviews/bundle/publish
     * Body: { "cycleId": "...", "subjectUserId": "..." }
     */
    @POST
    @Path("/bundle/publish")
    @Override
    public Response publishReviewBundle(PublishBundleRequest request) {
        if (!isAdmin() && !isManager()) return forbidden();
        if (request == null || request.getCycleId() == null || request.getSubjectUserId() == null) {
            return badRequest("'cycleId' and 'subjectUserId' are required");
        }

        String publishedBy = getUserId();
        List<Review> published;
        try {
            published = reviewService.publishBundle(
                    request.getCycleId(), request.getSubjectUserId(), publishedBy);
        } catch (jakarta.ws.rs.BadRequestException e) {
            // Service throws BadRequestException with a useful message (e.g.
            // "Cannot publish bundle - SELF review is DRAFT…"). Without this
            // catch, JAX-RS renders it as 400 with an empty body — leaving
            // both API consumers and E2E assertions blind to the cause.
            return badRequest(e.getMessage());
        } catch (jakarta.ws.rs.NotFoundException e) {
            // Same problem on the "no reviews for (cycle, subject)" path —
            // surface a JSON body so callers can branch on the message.
            return notFound(e.getMessage());
        }

        LOG.infof("Bundle published for subject=%s cycle=%s by %s",
                request.getSubjectUserId(), request.getCycleId(), publishedBy);
        notificationIntentPublisher.publishReviewPublished(
                request.getCycleId(), request.getSubjectUserId(), publishedBy);

        Map<String, Object> notificationJob = new LinkedHashMap<>();
        notificationJob.put("jobId", "PUBLISHED#" + request.getCycleId() + "#" + request.getSubjectUserId());
        notificationJob.put("taskType", "REVIEW_PUBLISHED");
        notificationJob.put("status", "PENDING");
        notificationJob.put("recipientUserId", request.getSubjectUserId());
        notificationJob.put("materialized", false);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "Bundle published successfully");
        body.put("reviews", published);
        body.put("notificationJobs", List.of(notificationJob));
        return Response.ok(body).build();
    }

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Create a new review.
     * - ADMIN: can create any review type.
     * - MANAGER: can create MANAGER reviews for their team.
     * - EMPLOYEE: can create SELF reviews for themselves only.
     */
    @POST
    @Override
    public Response createReview(CreateReviewRequest request) {
        String callerId = getUserId();
        if (callerId.isEmpty()) return unauthorized();

        if (!isAdmin() && !isManager()) {
            // Employee: can only create a SELF review where they are the subject
            if (request == null ||
                request.getReviewType() != CreateReviewRequest.ReviewTypeEnum.SELF ||
                !callerId.equals(request.getSubjectUserId())) {
                return forbidden();
            }
            // Force authorUserId to themselves
            request.setAuthorUserId(callerId);
        }

        if (!isAdmin()
                && request != null
                && request.getReviewType() == CreateReviewRequest.ReviewTypeEnum.MANAGER) {
            if (!isSubjectsManager(callerId, request.getSubjectUserId())) {
                return forbidden("You are not the manager of this employee.");
            }
            // Gate (self + all assigned peers complete) is ADVISORY, not a hard
            // block — a manager may create/submit their review even with
            // prerequisites outstanding, e.g. to beat a cycle deadline. The
            // GET /reviews/manager-gate endpoint still surfaces what's
            // outstanding so the UI can show a non-blocking heads-up.
        }

        Review created = reviewService.create(request, callerId);
        if (created == null) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new MessageResponse().message("Failed to create review"))
                    .build();
        }
        LOG.infof("Review %s created by %s", created.getReviewId(), callerId);
        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    // ── Update ────────────────────────────────────────────────────────────────

    /**
     * Update a review's answers, status, and/or rating.
     * The caller must be the author of the review, or an admin/manager.
     */
    @PUT
    @Path("/{reviewId}")
    @Override
    public Response updateReview(@PathParam("reviewId") String reviewId, UpdateReviewRequest request) {
        String callerId = getUserId();
        if (callerId.isEmpty()) return unauthorized();

        Review existing = reviewService.getReviewById(reviewId).orElse(null);
        if (existing == null) return notFound("Review not found: " + reviewId);

        // Only the assigned author, an admin, or the SUBJECT'S DIRECT manager
        // may update. A bare `isManager()` check is too wide — any manager-role
        // user could otherwise scribble on another team's drafts. Tightened
        // per code review M3 (2026-05-11).
        boolean isAuthor = callerId.equals(existing.getAuthorUserId());
        boolean isSubjectsMgr = existing.getSubjectUserId() != null
                && isSubjectsManager(callerId, existing.getSubjectUserId());
        if (!isAuthor && !isAdmin() && !isSubjectsMgr) return forbidden();

        boolean isManagerSubmit = existing.getReviewType() == Review.ReviewTypeEnum.MANAGER
                && request != null
                && request.getStatus() == UpdateReviewRequest.StatusEnum.COMPLETED;
        if (isManagerSubmit && !isAdmin()) {
            if (!isSubjectsManager(callerId, existing.getSubjectUserId())) {
                return forbidden("You are not the manager of this employee.");
            }
            // Gate is advisory — see createReview. The manager may submit even
            // when self/peer prerequisites are outstanding.
        }

        Review updated = reviewService.update(reviewId, request);
        return Response.ok(updated).build();
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /** Delete a review — admin only. */
    @DELETE
    @Path("/{reviewId}")
    @Override
    public Response deleteReview(@PathParam("reviewId") String reviewId) {
        if (!isAdmin()) return forbidden();

        if (reviewService.getReviewById(reviewId).isEmpty()) {
            return notFound("Review not found: " + reviewId);
        }

        reviewService.delete(reviewId);
        return Response.noContent().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new MessageResponse().message("Unauthorized"))
                .build();
    }

    /** Builds a review-list response that is null-safe (Map.of rejects null values).
     *
     *  <p>Does NOT strip answers. This helper serves the subject-keyed queries
     *  (`?subjectUserId=`), which are the detail views — the manager review
     *  form and cycle-summary read each review's answers to render the
     *  employee's responses and feed the AI flows. Cycle-keyed overviews
     *  ({@code ?cycleId=}, {@code /cycle/&#123;id&#125;}) and the employee
     *  task-list endpoints (/reviews/my-review, /reviews/assigned-to-me) strip
     *  answers at their own call sites — they only need status/ids. */
    private Response ok(List<Review> reviews) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("overallStatus", reviewService.deriveOverallStatus(reviews));
        body.put("reviews", reviews);
        return Response.ok(body).build();
    }

    /**
     * True when this review is fillable right now. SELF / MANAGER rows always
     * surface; PEER_360 rows only surface once their parent cycle is ACTIVE so
     * peers don't see assignments made against a still-SCHEDULED cycle.
     */
    private boolean isFillable(Review review) {
        if (review.getReviewType() != Review.ReviewTypeEnum.PEER_360) return true;
        return cycleService.getReviewCycle(review.getCycleId())
                .map(c -> c.getStatus() == ReviewCycle.StatusEnum.ACTIVE)
                .orElse(false);
    }
}

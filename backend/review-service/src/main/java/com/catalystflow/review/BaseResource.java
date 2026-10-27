package com.catalystflow.review;

import com.catalystflow.review.model.MessageResponse;
import com.catalystflow.review.model.Milestone;
import com.catalystflow.review.model.Review;
import com.catalystflow.review.model.ReviewCycle;
import com.catalystflow.review.model.ReviewTemplate;
import com.catalystflow.review.model.TemplateQuestion;
import com.catalystflow.review.model.TemplateSnapshot;
import com.catalystflow.review.service.UserLookup;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Shared base class for all JAX-RS resources in the review-service.
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

    @Inject
    UserLookup userLookup;

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

    /**
     * True if {@code callerId} is the direct manager of {@code subjectUserId}
     * (subject's {@code reportingTo} equals callerId). Fail-closed: returns false
     * on null input or lookup failure, so a missing user record can never bypass
     * the check. Shared by every resource needing subject-scoped authorization.
     */
    protected boolean isSubjectsManager(String callerId, String subjectUserId) {
        if (callerId == null || subjectUserId == null) return false;
        return userLookup.get(subjectUserId)
                .map(UserLookup.UserSummary::reportingTo)
                .map(callerId::equals)
                .orElse(false);
    }

    // ── Response factories ────────────────────────────────────────────────────

    protected Response forbidden() {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(new MessageResponse().message("Forbidden: ADMIN role required"))
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

    protected Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new MessageResponse().message(message))
                .build();
    }

    // ── Response projection helpers ──────────────────────────────────────────
    //
    // Field-strip approach (no new DTO classes): null out sensitive fields on
    // the domain object before returning it. Combined with the per-service
    // `quarkus.jackson.serialization-inclusion=non-null`, the field is omitted
    // from the wire entirely. Mutates the passed-in instance — safe because
    // each call site fetches a fresh copy from DynamoDB per request.

    /**
     * For non-ADMIN callers: removes the AI-rubric "answer key" from every
     * question embedded in the cycle's milestone template snapshots.
     * ADMIN callers receive the unmodified object.
     */
    protected void stripIdealResponses(ReviewCycle cycle) {
        if (isAdmin() || cycle == null || cycle.getMilestones() == null) return;
        for (Milestone m : cycle.getMilestones()) {
            if (m == null) continue;
            TemplateSnapshot snap = m.getTemplateSnapshot();
            if (snap == null || snap.getQuestions() == null) continue;
            for (TemplateQuestion q : snap.getQuestions()) {
                if (q != null) q.setIdealResponse(null);
            }
        }
    }

    /** List variant of {@link #stripIdealResponses(ReviewCycle)}. */
    protected void stripIdealResponses(List<ReviewCycle> cycles) {
        if (isAdmin() || cycles == null) return;
        for (ReviewCycle c : cycles) stripIdealResponses(c);
    }

    /**
     * For non-ADMIN callers: removes idealResponse from every template
     * question. Used by GET /templates/{id} where managers + employees may
     * need to render questions but must not see the AI rubric.
     */
    protected void stripIdealResponses(ReviewTemplate template) {
        if (isAdmin() || template == null || template.getQuestions() == null) return;
        for (TemplateQuestion q : template.getQuestions()) {
            if (q != null) q.setIdealResponse(null);
        }
    }

    /**
     * Removes the answers payload from a cross-subject cycle-overview row.
     * Applied ONLY to the cycle-keyed lists ({@code GET /reviews?cycleId=},
     * {@code /reviews/cycle/{id}}) — those span many subjects and the UI
     * (dashboard manager tasks, insights aggregation) renders status/ids only,
     * so dropping answer bodies is a real payload win.
     *
     * <p>Deliberately NOT applied to endpoints that return an individual's own
     * reviews — {@code ?subjectUserId=} (manager review form + cycle-summary),
     * {@code /reviews/my-review} (employee reading their published manager
     * feedback + own self-review), and {@code /reviews/assigned-to-me}
     * (employee resuming a draft). Those callers are authorized to see the
     * content and the UI renders it; stripping there is all cost, no benefit.
     * The full answer content always remains on {@code GET /reviews/{reviewId}}.
     */
    protected void stripAnswers(Review review) {
        if (review != null) review.setAnswers(null);
    }

    /** List variant of {@link #stripAnswers(Review)}. */
    protected void stripAnswers(List<Review> reviews) {
        if (reviews == null) return;
        for (Review r : reviews) stripAnswers(r);
    }
}

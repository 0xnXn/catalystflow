package com.catalystflow.ai;

import com.catalystflow.ai.api.AiReviewApi;
import com.catalystflow.ai.model.AiReviewInsightsRequest;
import com.catalystflow.ai.model.AiReviewInsightsStatusResponse;
import com.catalystflow.ai.model.AiReviewJobResponse;
import com.catalystflow.ai.model.AiReviewRequest;
import com.catalystflow.ai.model.AiReviewStatusResponse;
import com.catalystflow.ai.model.DisciplineInfo;
import com.catalystflow.ai.model.MessageResponse;
import com.catalystflow.ai.model.QuestionSuggestRequest;
import com.catalystflow.ai.service.AiReviewService;
import com.catalystflow.ai.service.JobNotCompletedException;
import com.catalystflow.ai.service.ReviewLockedException;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

@Path("/ai-review")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AiReviewResource extends BaseResource implements AiReviewApi {

    private static final Logger LOG = Logger.getLogger(AiReviewResource.class);

    @Inject
    AiReviewService aiReviewService;

    @GET
    @Path("/disciplines")
    @Override
    public Response getAiReviewDisciplines() {
        try {
            List<DisciplineInfo> disciplines = aiReviewService.getDisciplines();
            return Response.ok(disciplines).build();
        } catch (Exception e) {
            LOG.error("Failed to load disciplines", e);
            return Response.serverError().entity(new MessageResponse().message("Failed to load disciplines")).build();
        }
    }

    @POST
    @Path("/analyze")
    @Override
    public Response analyzeAiReview(AiReviewRequest request) {
        String userId = getUserId();
        if (userId.isEmpty()) return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new MessageResponse().message("Unauthorized")).build();

        if (request == null || request.getQuestions() == null || request.getQuestions().isEmpty())
            return badRequest("'questions' must not be empty");
        if (request.getSeniority() == null || request.getSeniority().isBlank())
            return badRequest("'seniority' is required");
        if (request.getDiscipline() == null || request.getDiscipline().isBlank())
            return badRequest("'discipline' is required");

        try {
            AiReviewJobResponse job = aiReviewService.startAnalysis(request);
            return Response.accepted(job).build();
        } catch (BadRequestException e) {
            return badRequest(e.getMessage());
        } catch (WebApplicationException e) {
            return Response.status(e.getResponse().getStatus())
                    .entity(new MessageResponse().message(e.getMessage())).build();
        } catch (Exception e) {
            LOG.error("Unexpected error starting AI review analysis", e);
            return Response.serverError().entity(new MessageResponse().message("Unexpected error")).build();
        }
    }

    @GET
    @Path("/analyze/{jobId}/status")
    @Override
    public Response getAiReviewAnalysisStatus(@PathParam("jobId") String jobId) {
        String userId = getUserId();
        if (userId.isEmpty()) return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new MessageResponse().message("Unauthorized")).build();

        AiReviewStatusResponse status = aiReviewService.getAnalysisStatus(jobId);
        if (status == null) {
            return notFound("AI review job not found");
        }
        return Response.ok(status).build();
    }

    @POST
    @Path("/insights")
    @Override
    public Response generateAiReviewInsights(AiReviewInsightsRequest request) {
        String userId = getUserId();
        if (userId.isEmpty()) return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new MessageResponse().message("Unauthorized")).build();

        if (request == null || request.getQuestions() == null || request.getQuestions().isEmpty())
            return badRequest("'questions' must not be empty");
        if (request.getSeniority() == null || request.getSeniority().isBlank())
            return badRequest("'seniority' is required");
        if (request.getDiscipline() == null || request.getDiscipline().isBlank())
            return badRequest("'discipline' is required");

        try {
            AiReviewJobResponse job = aiReviewService.startInsights(request);
            return Response.accepted(job).build();
        } catch (BadRequestException e) {
            return badRequest(e.getMessage());
        } catch (WebApplicationException e) {
            return Response.status(e.getResponse().getStatus())
                    .entity(new MessageResponse().message(e.getMessage())).build();
        } catch (Exception e) {
            LOG.error("Unexpected error starting AI review insights", e);
            return Response.serverError().entity(new MessageResponse().message("Unexpected error")).build();
        }
    }

    @GET
    @Path("/insights/{jobId}/status")
    @Override
    public Response getAiReviewInsightsStatus(@PathParam("jobId") String jobId) {
        String userId = getUserId();
        if (userId.isEmpty()) return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new MessageResponse().message("Unauthorized")).build();

        AiReviewInsightsStatusResponse status = aiReviewService.getInsightsStatus(jobId);
        if (status == null) {
            return notFound("AI review job not found");
        }
        return Response.ok(status).build();
    }

    /**
     * Loose-typed job status for manager flows. The /analyze/{jobId}/status
     * endpoint binds the cached result into {@link AiReviewResponse}, which
     * silently drops fields for non-analyze taskTypes. This endpoint returns
     * the result as a raw JSON object so manager pollers see the right shape.
     */
    @GET
    @Path("/manager/jobs/{jobId}/status")
    public Response getManagerJobStatus(@PathParam("jobId") String jobId) {
        String userId = getUserId();
        if (userId.isEmpty()) return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new MessageResponse().message("Unauthorized")).build();
        if (!isAdmin() && !isManager()) return forbidden();
        if (!ownsJob(userId, jobId)) return notFound("AI job not found");

        Map<String, Object> body = aiReviewService.getJobStatus(jobId);
        if (body == null) return notFound("AI job not found");
        return Response.ok(body).build();
    }

    /**
     * Update the resultJson on a COMPLETED manager-job row — used when the
     * manager edits the AI Narrative draft and clicks Save. Auth matches the
     * status endpoint above (any manager or admin). Body must deserialize as
     * {@link com.catalystflow.ai.model.ManagerGoldenReview}.
     *
     * <p>Returns the persisted ManagerGoldenReview so the frontend re-renders
     * from canonical server state. 400 on shape failure, 409 on non-COMPLETED row.
     */
    @PUT
    @Path("/manager/jobs/{jobId}/result")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateManagerJobResult(
            @PathParam("jobId") String jobId,
            com.catalystflow.ai.model.ManagerGoldenReview body) {
        String userId = getUserId();
        if (userId.isEmpty()) return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new MessageResponse().message("Unauthorized")).build();
        if (!isAdmin() && !isManager()) return forbidden();
        if (!ownsJob(userId, jobId)) return notFound("AI job not found");

        try {
            com.catalystflow.ai.model.ManagerGoldenReview saved =
                    aiReviewService.updateManagerGoldenResult(jobId, body);
            return Response.ok(saved).build();
        } catch (ReviewLockedException e) {
            // BUG-001: review is COMPLETED/PUBLISHED — narrative is frozen.
            LOG.warnf("Manager golden edit rejected (review locked) for job %s: %s", jobId, e.getMessage());
            return Response.status(Response.Status.CONFLICT)
                    .entity(new MessageResponse().message(e.getMessage())).build();
        } catch (JobNotCompletedException e) {
            // Row exists but isn't COMPLETED yet — distinct from a malformed body.
            LOG.warnf("Manager golden edit rejected (job %s not COMPLETED): %s", jobId, e.getMessage());
            return Response.status(Response.Status.CONFLICT)
                    .entity(new MessageResponse().message(e.getMessage())).build();
        } catch (BadRequestException e) {
            LOG.warnf("Manager golden edit rejected for job %s: %s", jobId, e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new MessageResponse().message(e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Manager golden edit failed for job %s", jobId);
            return Response.serverError()
                    .entity(new MessageResponse().message("Unexpected error")).build();
        }
    }

    /**
     * Ownership gate for {@code /manager/jobs/{jobId}} read &amp; write. A manager
     * may only touch a job they requested; admins bypass. Resolves the owner from
     * the persisted {@code requestedBy} via {@link AiReviewService#getJobOwner}.
     * Returns false — and callers respond <b>404</b> (not 403) — when the caller
     * is neither admin nor owner, so a non-owner can't tell "exists but not
     * yours" from "missing" and enumerate jobIds.
     */
    private boolean ownsJob(String userId, String jobId) {
        if (isAdmin()) return true;
        String owner = aiReviewService.getJobOwner(jobId);
        return owner != null && owner.equals(userId);
    }

    // ── Manager guidance endpoints (G3 — async via SQS) ──────────────────────
    //
    // POST handlers enqueue a job and return 202 + jobId immediately. The
    // AiWorker Lambda picks up the message, runs Gemini, and writes the result
    // back to AiJobs. Frontend polls /ai-review/analyze/{jobId}/status until
    // COMPLETED — same polling endpoint shared with /analyze and /insights.
    //
    // Sync-style GET endpoints removed in G3 — the polling endpoint replaces
    // them. The deterministic "manager-analyze-{reviewId}" jobId pattern is gone
    // along with them; UUID jobIds are now the canonical handle.

    @POST
    @Path("/manager/reviews/{reviewId}/golden")
    public Response managerGolden(@PathParam("reviewId") String reviewId) {
        String userId = getUserId();
        if (userId.isEmpty()) return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new MessageResponse().message("Unauthorized")).build();
        if (!isAdmin() && !isManager()) return forbidden();

        try {
            AiReviewJobResponse job = aiReviewService.startManagerGolden(reviewId, userId);
            return Response.accepted(job).build();
        } catch (ReviewLockedException e) {
            // BUG-001: review is COMPLETED/PUBLISHED — narrative cannot be regenerated.
            LOG.warnf("Manager golden regenerate rejected (review locked) for review %s: %s", reviewId, e.getMessage());
            return Response.status(Response.Status.CONFLICT)
                    .entity(new MessageResponse().message(e.getMessage())).build();
        } catch (Exception e) {
            LOG.errorf(e, "Manager golden enqueue failed for review %s", reviewId);
            return Response.serverError().entity(new MessageResponse().message("Unexpected error")).build();
        }
    }

    /**
     * Peer-360 digest (ADR-2). Enqueues a {@code PEER_360_DIGEST} job that
     * clusters every completed PEER_360 row for a (cycleId, subjectUserId)
     * pair into themes/consensus/dissent. Returns 202 + jobId; the frontend
     * polls /ai-review/manager/jobs/{jobId}/status.
     *
     * RBAC matches the other manager endpoints today: any manager or admin.
     * Tightening to "subject's manager only" is tracked under the AI-job
     * ownership work in end-to-end-flow.md §3.6.
     */
    @POST
    @Path("/manager/cycles/{cycleId}/subjects/{subjectUserId}/peer360-digest")
    public Response managerPeer360Digest(@PathParam("cycleId") String cycleId,
                                         @PathParam("subjectUserId") String subjectUserId) {
        String userId = getUserId();
        if (userId.isEmpty()) return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new MessageResponse().message("Unauthorized")).build();
        if (!isAdmin() && !isManager()) return forbidden();
        if (cycleId == null || cycleId.isBlank()) return badRequest("cycleId is required");
        if (subjectUserId == null || subjectUserId.isBlank()) return badRequest("subjectUserId is required");

        try {
            AiReviewJobResponse job = aiReviewService.startPeer360Digest(cycleId, subjectUserId, userId);
            return Response.accepted(job).build();
        } catch (Exception e) {
            LOG.errorf(e, "Peer-360 digest enqueue failed for cycle %s subject %s", cycleId, subjectUserId);
            return Response.serverError().entity(new MessageResponse().message("Unexpected error")).build();
        }
    }

    /**
     * Resolve the latest COMPLETED peer-360 digest for a (cycleId, subjectUserId)
     * so the UI reuses a finished digest on refresh without a review-stored
     * pointer (the digest is a (subject, cycle) artifact, not a per-review one).
     * {@code 200 { jobId }} when one exists, {@code 204} when none. Same RBAC as
     * the enqueue (admin/manager); the manager-gated status poll then serves the
     * content, so an employee can never read peer feedback via this jobId.
     */
    @GET
    @Path("/manager/cycles/{cycleId}/subjects/{subjectUserId}/peer360-digest/latest")
    public Response latestPeer360Digest(@PathParam("cycleId") String cycleId,
                                        @PathParam("subjectUserId") String subjectUserId) {
        String userId = getUserId();
        if (userId.isEmpty()) return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new MessageResponse().message("Unauthorized")).build();
        if (!isAdmin() && !isManager()) return forbidden();
        if (cycleId == null || cycleId.isBlank()) return badRequest("cycleId is required");
        if (subjectUserId == null || subjectUserId.isBlank()) return badRequest("subjectUserId is required");

        String jobId = aiReviewService.latestCompletedPeer360Digest(cycleId, subjectUserId);
        if (jobId == null) return Response.noContent().build();
        AiReviewJobResponse resp = new AiReviewJobResponse();
        resp.setJobId(jobId);   // status omitted — resolve only ever returns a completed digest
        return Response.ok(resp).build();
    }

    @POST
    @Path("/manager/reviews/{reviewId}/question-suggest")
    public Response managerQuestionSuggest(@PathParam("reviewId") String reviewId,
                                           QuestionSuggestRequest req) {
        String userId = getUserId();
        if (userId.isEmpty()) return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new MessageResponse().message("Unauthorized")).build();
        if (!isAdmin() && !isManager()) return forbidden();
        if (req == null || req.questionText == null || req.questionText.isBlank())
            return badRequest("questionText is required");
        if (req.questionId == null || req.questionId.isBlank())
            return badRequest("questionId is required");
        if (req.managerCurrentAnswer == null || req.managerCurrentAnswer.isBlank())
            return badRequest("managerCurrentAnswer is required");
        if (req.discipline == null || req.discipline.isBlank())
            return badRequest("discipline is required");
        if (req.seniority == null || req.seniority.isBlank())
            return badRequest("seniority is required");
        if (req.employeeReview == null || req.employeeReview.answers == null || req.employeeReview.answers.isEmpty())
            return badRequest("employeeReview.answers is required");

        try {
            AiReviewJobResponse job = aiReviewService.startManagerQuestionSuggest(reviewId, req);
            return Response.accepted(job).build();
        } catch (Exception e) {
            LOG.errorf(e, "Manager question-suggest enqueue failed for review %s", reviewId);
            return Response.serverError().entity(new MessageResponse().message("Unexpected error")).build();
        }
    }

    // ── CC-6: Gemini context-cache admin (admin-only) ──────────────────────────
    //
    // Operator escape hatch. Both endpoints gate on isAdmin() inline (no
    // requireAdmin() helper yet). Manager
    // and Employee roles get 403; this is the only AI Review endpoint group
    // that's strictly admin-only (the rest accept manager too).

    @jakarta.inject.Inject
    com.catalystflow.ai.service.GeminiCacheService geminiCacheService;

    /**
     * CC-6 (AC-12.2 — revised round-3): list every row in the Gemini
     * context cache table. Returns both real CACHE rows and
     * UNDER_THRESHOLD tombstones so the operator can see the full working
     * set. Expired rows are reaped by DDB TTL so this view is always
     * current.
     *
     * <p><b>Scope</b>: row inventory only — per-entry hit-rate / hit-count
     * is NOT in this response. AC-12.2 originally promised "hit-rate
     * stats", revised here: per-entry hit tracking would require a DDB
     * UpdateItem on every cache hit (latency + cost). Aggregate hit-rate
     * is already available via the CC-5 CloudWatch EMF metric
     * {@code CatalystFlow/AI/CacheHit} (dimensions {@code taskType +
     * stage}). The {@code CatalystFlow-Ai-CacheHitRate-Low} alarm watches
     * the SUM ratio. Per-entry counters can be added in a follow-up as
     * additive fields on {@link com.catalystflow.ai.model.CacheEntryDto}
     * if operators need per-bucket drilldown.
     */
    @GET
    @Path("/cache")
    public Response listCacheEntries() {
        String userId = getUserId();
        if (userId.isEmpty()) return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new MessageResponse().message("Unauthorized")).build();
        if (!isAdmin()) return forbidden();

        try {
            java.util.List<com.catalystflow.ai.repository.GeminiContextCacheRepository.CacheEntry> rows =
                    geminiCacheService.listAll();
            com.catalystflow.ai.model.CacheEntryListResponse response =
                    new com.catalystflow.ai.model.CacheEntryListResponse();
            response.setCount(rows.size());
            response.setEntries(rows.stream()
                    .map(AiReviewResource::toDto)
                    .toList());
            return Response.ok(response).build();
        } catch (Exception e) {
            LOG.errorf(e, "CC-6 admin: cache list failed");
            return Response.serverError()
                    .entity(new MessageResponse().message("Failed to list cache entries"))
                    .build();
        }
    }

    /**
     * CC-6 (AC-12.1): hard-delete a cache row by full cacheKey + (for CACHE
     * rows) the upstream Gemini resource. UNDER_THRESHOLD tombstones skip
     * the Gemini DELETE entirely — no upstream resource exists.
     *
     * <p>Returns 204 on success (row existed and was deleted), 404 when no
     * row exists for that cacheKey, 403 for non-admin callers. Upstream
     * Gemini DELETE failure does NOT 500 the call — the DDB row is removed
     * regardless and the next resolveOrCreate forces a fresh create.
     */
    @DELETE
    @Path("/cache/{cacheKey}")
    public Response deleteCacheEntry(@PathParam("cacheKey") String cacheKey) {
        String userId = getUserId();
        if (userId.isEmpty()) return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new MessageResponse().message("Unauthorized")).build();
        if (!isAdmin()) return forbidden();

        try {
            boolean existed = geminiCacheService.delete(cacheKey);
            if (!existed) {
                return notFound("No cache entry with that cacheKey");
            }
            LOG.infof("CC-6 admin: cache entry deleted by %s — cacheKey=%s", userId, cacheKey);
            return Response.noContent().build();
        } catch (IllegalStateException e) {
            // GeminiCacheService.delete propagates DDB failures as IllegalStateException —
            // surface as 500 so operators see the real error. Upstream Gemini
            // failures are swallowed by the transport and never reach here.
            LOG.errorf(e, "CC-6 admin: DDB failure on cache delete — cacheKey=%s", cacheKey);
            return Response.serverError()
                    .entity(new MessageResponse().message("Failed to delete cache entry"))
                    .build();
        }
    }

    /** Map repository CacheEntry → wire DTO. Kept inline since it's only used here. */
    private static com.catalystflow.ai.model.CacheEntryDto toDto(
            com.catalystflow.ai.repository.GeminiContextCacheRepository.CacheEntry row) {
        com.catalystflow.ai.model.CacheEntryDto dto = new com.catalystflow.ai.model.CacheEntryDto();
        dto.setCacheKey(row.cacheKey());
        dto.setCacheName(row.cacheName());
        dto.setTaskType(com.catalystflow.ai.model.CacheEntryDto.TaskTypeEnum.fromValue(row.taskType()));
        dto.setPromptVersion(row.promptVersion());
        dto.setContentHash(row.contentHash());
        // CacheEntry doesn't store discipline/seniority directly — they're
        // encoded in cacheKey. Leave the DTO fields null; the cacheKey
        // string already carries them for forensic decoding.
        dto.setCreatedAt(row.createdAt());
        dto.setExpiresAt(row.expiresAt());
        dto.setCachedTokens(row.cachedTokens());
        dto.setEntryType(com.catalystflow.ai.model.CacheEntryDto.EntryTypeEnum.fromValue(row.entryType()));
        return dto;
    }
}

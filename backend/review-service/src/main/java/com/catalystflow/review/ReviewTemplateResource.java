package com.catalystflow.review;

import com.catalystflow.review.api.ReviewTemplatesApi;
import com.catalystflow.review.model.CreateReviewTemplateRequest;
import com.catalystflow.review.model.MessageResponse;
import com.catalystflow.review.model.ReviewTemplate;
import com.catalystflow.review.model.UpdateReviewTemplateRequest;
import com.catalystflow.review.service.ReviewTemplateService;
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

import java.util.Map;

@Path("/templates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReviewTemplateResource extends BaseResource implements ReviewTemplatesApi {

    private static final Logger LOG = Logger.getLogger(ReviewTemplateResource.class);

    @Inject
    ReviewTemplateService reviewTemplateService;

    @Override
    @GET
    @Path("/health")
    public Response getTemplateHealth() {
        return Response.ok(new MessageResponse().message("Template Service is Online")).build();
    }

    @Override
    @GET
    @Path("/check-name")
    public Response checkTemplateName(
            @QueryParam("name") String name,
            @QueryParam("excludeId") String excludeId) {

        if (!isAdmin()) return forbidden();

        if (name == null || name.isBlank()) {
            return badRequest("Query parameter 'name' is required.");
        }

        boolean available = !reviewTemplateService.isNameTaken(name, excludeId);
        return Response.ok(Map.of("available", available)).build();
    }

    @Override
    @GET
    public Response listReviewTemplates(@QueryParam("reviewType") String reviewType) {
        if (!isAdmin()) return forbidden();

        try {
            var templates = reviewTemplateService.getAllTemplates(reviewType);
            return Response.ok(Map.of("templates", templates, "count", templates.size())).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @Override
    @POST
    public Response createReviewTemplate(CreateReviewTemplateRequest request) {
        if (!isAdmin()) return forbidden();

        try {
            ReviewTemplate created = reviewTemplateService.createTemplate(toTemplate(request));
            return Response.status(Response.Status.CREATED).entity(created).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            LOG.error("Failed to create review template", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new MessageResponse().message(e.getMessage()))
                    .build();
        }
    }

    @Override
    @GET
    @Path("/{templateId}")
    public Response getReviewTemplate(@PathParam("templateId") String templateId) {
        String userId = getUserId();
        if (userId == null || userId.isBlank()) return forbidden();

        // Authz stays "any authenticated user" because the employee self-review
        // fill page (review-form.component.ts:131) falls back to this endpoint
        // when milestone.templateSnapshot.questions is empty. Tightening to
        // ADMIN|MANAGER would break that edge case. The actual harm (AI
        // grading rubric leak) is closed by stripIdealResponses for non-admin.
        return reviewTemplateService.getTemplate(templateId)
                .map(t -> {
                    stripIdealResponses(t);
                    return Response.ok(t).build();
                })
                .orElse(notFound("Template not found."));
    }

    @Override
    @PUT
    @Path("/{templateId}")
    public Response updateReviewTemplate(
            @PathParam("templateId") String templateId,
            UpdateReviewTemplateRequest request) {

        if (!isAdmin()) return forbidden();

        try {
            return reviewTemplateService.updateTemplate(templateId, toTemplate(request))
                    .map(t -> Response.ok(t).build())
                    .orElse(notFound("Template not found."));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            LOG.error("Failed to update review template", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new MessageResponse().message(e.getMessage()))
                    .build();
        }
    }

    @Override
    @DELETE
    @Path("/{templateId}")
    public Response deleteReviewTemplate(@PathParam("templateId") String templateId) {
        if (!isAdmin()) return forbidden();

        boolean deleted = reviewTemplateService.deleteTemplate(templateId);
        return deleted ? Response.noContent().build() : notFound("Template not found.");
    }

    private ReviewTemplate toTemplate(CreateReviewTemplateRequest request) {
        ReviewTemplate template = new ReviewTemplate();
        if (request == null) return template;
        template.setTemplateName(request.getTemplateName());
        if (request.getReviewType() != null) {
            template.setReviewType(ReviewTemplate.ReviewTypeEnum.fromValue(request.getReviewType().value()));
        }
        template.setDescription(request.getDescription());
        template.setQuestions(request.getQuestions());
        return template;
    }

    private ReviewTemplate toTemplate(UpdateReviewTemplateRequest request) {
        ReviewTemplate template = new ReviewTemplate();
        if (request == null) return template;
        template.setTemplateName(request.getTemplateName());
        if (request.getReviewType() != null) {
            template.setReviewType(ReviewTemplate.ReviewTypeEnum.fromValue(request.getReviewType().value()));
        }
        template.setDescription(request.getDescription());
        template.setQuestions(request.getQuestions());
        return template;
    }
}

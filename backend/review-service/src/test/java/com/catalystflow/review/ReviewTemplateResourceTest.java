package com.catalystflow.review;

import com.catalystflow.review.model.ReviewTemplate;
import com.catalystflow.review.model.TemplateQuestion;
import com.catalystflow.review.service.ReviewTemplateService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REST-layer tests for {@link ReviewTemplateResource}. Verifies auth gates,
 * status codes, and error handling. Service layer mocked via {@code @InjectMock}.
 */
@QuarkusTest
class ReviewTemplateResourceTest {

    @InjectMock
    ReviewTemplateService templateService;

    @InjectMock
    ClaimsContext claimsContext;

    @BeforeEach
    void setUp() {
        reset(templateService);
        when(claimsContext.isAdmin()).thenReturn(false);
        when(claimsContext.isManager()).thenReturn(false);
        when(claimsContext.getUserId()).thenReturn("");
    }

    private void asAdmin() {
        when(claimsContext.isAdmin()).thenReturn(true);
        when(claimsContext.getUserId()).thenReturn("admin-id");
    }

    private void asEmployee() {
        when(claimsContext.isAdmin()).thenReturn(false);
        when(claimsContext.getUserId()).thenReturn("emp-id");
    }

    private ReviewTemplate sample(UUID id, String name) {
        ReviewTemplate t = new ReviewTemplate();
        t.setTemplateId(id);
        t.setTemplateName(name);
        t.setReviewType(ReviewTemplate.ReviewTypeEnum.SELF);
        t.setCreatedAt("2026-01-01T00:00:00Z");
        t.setUpdatedAt("2026-01-01T00:00:00Z");
        t.setUsageCount(0);
        return t;
    }

    private Map<String, Object> validCreatePayload() {
        return Map.of(
                "templateName", "Quarterly Self",
                "reviewType",   "SELF",
                "description",  "Self assessment",
                "questions",    List.of(Map.of(
                        "text", "What did you do?",
                        "type", "TEXT"
                ))
        );
    }

    // ── health ───────────────────────────────────────────────────────────────

    @Test
    void health_returns200() {
        given()
            .when().get("/templates/health")
            .then()
            .statusCode(200)
            .body("message", containsString("Template Service is Online"));
    }

    // ── check-name ───────────────────────────────────────────────────────────

    @Test
    void checkName_nonAdmin_returns403() {
        asEmployee();
        given()
            .queryParam("name", "Anything")
            .when().get("/templates/check-name")
            .then().statusCode(403);
    }

    @Test
    void checkName_admin_missingName_returns400() {
        asAdmin();
        given()
            .when().get("/templates/check-name")
            .then().statusCode(400);
    }

    @Test
    void checkName_admin_blankName_returns400() {
        asAdmin();
        given()
            .queryParam("name", "  ")
            .when().get("/templates/check-name")
            .then().statusCode(400);
    }

    @Test
    void checkName_admin_available_returnsTrue() {
        asAdmin();
        when(templateService.isNameTaken("Free", null)).thenReturn(false);

        given()
            .queryParam("name", "Free")
            .when().get("/templates/check-name")
            .then()
            .statusCode(200)
            .body("available", equalTo(true));
    }

    @Test
    void checkName_admin_taken_returnsFalse() {
        asAdmin();
        when(templateService.isNameTaken("Taken", "exclude-id")).thenReturn(true);

        given()
            .queryParam("name", "Taken")
            .queryParam("excludeId", "exclude-id")
            .when().get("/templates/check-name")
            .then()
            .statusCode(200)
            .body("available", equalTo(false));
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Test
    void list_nonAdmin_returns403() {
        asEmployee();
        given()
            .when().get("/templates")
            .then().statusCode(403);
        verify(templateService, never()).getAllTemplates(any());
    }

    @Test
    void list_admin_returnsTemplatesWithCount() {
        asAdmin();
        when(templateService.getAllTemplates(null))
                .thenReturn(List.of(sample(UUID.randomUUID(), "A"), sample(UUID.randomUUID(), "B")));

        given()
            .when().get("/templates")
            .then()
            .statusCode(200)
            .body("count", equalTo(2))
            .body("templates.size()", equalTo(2));
    }

    @Test
    void list_admin_invalidFilter_returns400() {
        asAdmin();
        when(templateService.getAllTemplates("BAD"))
                .thenThrow(new IllegalArgumentException("Invalid reviewType. Allowed: [SELF, MANAGER, PEER_360]"));

        given()
            .queryParam("reviewType", "BAD")
            .when().get("/templates")
            .then()
            .statusCode(400)
            .body("message", containsString("Invalid reviewType"));
    }

    @Test
    void list_admin_validFilter_passesThrough() {
        asAdmin();
        when(templateService.getAllTemplates("MANAGER"))
                .thenReturn(List.of(sample(UUID.randomUUID(), "Mgr Tpl")));

        given()
            .queryParam("reviewType", "MANAGER")
            .when().get("/templates")
            .then()
            .statusCode(200)
            .body("count", equalTo(1));
        verify(templateService, times(1)).getAllTemplates("MANAGER");
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    void create_nonAdmin_returns403() {
        asEmployee();
        given()
            .contentType(ContentType.JSON)
            .body(validCreatePayload())
            .when().post("/templates")
            .then().statusCode(403);
        verify(templateService, never()).createTemplate(any());
    }

    @Test
    void create_admin_returns201() {
        asAdmin();
        UUID id = UUID.randomUUID();
        when(templateService.createTemplate(any())).thenReturn(sample(id, "Quarterly Self"));

        given()
            .contentType(ContentType.JSON)
            .body(validCreatePayload())
            .when().post("/templates")
            .then()
            .statusCode(201)
            .body("templateId", equalTo(id.toString()))
            .body("templateName", equalTo("Quarterly Self"));
    }

    @Test
    void create_admin_validationFailure_returns400() {
        asAdmin();
        when(templateService.createTemplate(any()))
                .thenThrow(new IllegalArgumentException("templateName is required."));

        given()
            .contentType(ContentType.JSON)
            .body(validCreatePayload())
            .when().post("/templates")
            .then()
            .statusCode(400)
            .body("message", containsString("templateName"));
    }

    @Test
    void create_admin_unexpectedFailure_returns500() {
        asAdmin();
        when(templateService.createTemplate(any()))
                .thenThrow(new RuntimeException("DynamoDB unavailable"));

        given()
            .contentType(ContentType.JSON)
            .body(validCreatePayload())
            .when().post("/templates")
            .then()
            .statusCode(500);
    }

    // ── get ──────────────────────────────────────────────────────────────────

    @Test
    void get_anonymous_returns403() {
        // default ClaimsContext returns empty userId
        given()
            .when().get("/templates/whatever")
            .then().statusCode(403);
    }

    @Test
    void get_authenticated_found_returns200() {
        asEmployee();
        UUID id = UUID.randomUUID();
        when(templateService.getTemplate(id.toString()))
                .thenReturn(Optional.of(sample(id, "Q3 Self")));

        given()
            .when().get("/templates/" + id)
            .then()
            .statusCode(200)
            .body("templateId", equalTo(id.toString()));
    }

    @Test
    void get_authenticated_notFound_returns404() {
        asEmployee();
        when(templateService.getTemplate(any())).thenReturn(Optional.empty());

        given()
            .when().get("/templates/missing")
            .then().statusCode(404);
    }

    // ── update ───────────────────────────────────────────────────────────────

    @Test
    void update_nonAdmin_returns403() {
        asEmployee();
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("templateName", "New Name"))
            .when().put("/templates/some-id")
            .then().statusCode(403);
        verify(templateService, never()).updateTemplate(any(), any());
    }

    @Test
    void update_admin_found_returns200() {
        asAdmin();
        UUID id = UUID.randomUUID();
        when(templateService.updateTemplate(eq(id.toString()), any()))
                .thenReturn(Optional.of(sample(id, "Updated Name")));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("templateName", "Updated Name"))
            .when().put("/templates/" + id)
            .then()
            .statusCode(200)
            .body("templateName", equalTo("Updated Name"));
    }

    @Test
    void update_admin_notFound_returns404() {
        asAdmin();
        when(templateService.updateTemplate(any(), any())).thenReturn(Optional.empty());

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("templateName", "Renamed"))
            .when().put("/templates/missing")
            .then().statusCode(404);
    }

    @Test
    void update_admin_validationFailure_returns400() {
        asAdmin();
        when(templateService.updateTemplate(any(), any()))
                .thenThrow(new IllegalArgumentException("Duplicate name"));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("templateName", "Duplicate"))
            .when().put("/templates/some-id")
            .then()
            .statusCode(400)
            .body("message", containsString("Duplicate"));
    }

    @Test
    void update_admin_unexpectedFailure_returns500() {
        asAdmin();
        when(templateService.updateTemplate(any(), any()))
                .thenThrow(new RuntimeException("Boom"));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("templateName", "Anything"))
            .when().put("/templates/some-id")
            .then().statusCode(500);
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    void delete_nonAdmin_returns403() {
        asEmployee();
        given()
            .when().delete("/templates/some-id")
            .then().statusCode(403);
        verify(templateService, never()).deleteTemplate(any());
    }

    @Test
    void delete_admin_found_returns204() {
        asAdmin();
        when(templateService.deleteTemplate("doomed")).thenReturn(true);

        given()
            .when().delete("/templates/doomed")
            .then().statusCode(204);
    }

    @Test
    void delete_admin_notFound_returns404() {
        asAdmin();
        when(templateService.deleteTemplate("missing")).thenReturn(false);

        given()
            .when().delete("/templates/missing")
            .then().statusCode(404);
    }

    // ── request body mapping (covers toTemplate branches) ────────────────────

    @Test
    void create_admin_minimalBody_passesEmptyOptionalsToService() {
        asAdmin();
        UUID id = UUID.randomUUID();
        when(templateService.createTemplate(any())).thenReturn(sample(id, "Tiny"));

        // No description, no questions — the resource just maps and forwards
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("templateName", "Tiny", "reviewType", "SELF"))
            .when().post("/templates")
            .then().statusCode(201);
    }

    @Test
    void update_admin_minimalBody_passesEmptyOptionalsToService() {
        asAdmin();
        UUID id = UUID.randomUUID();
        when(templateService.updateTemplate(eq(id.toString()), any()))
                .thenReturn(Optional.of(sample(id, "Anything")));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of()) // empty patch — toTemplate handles all-null
            .when().put("/templates/" + id)
            .then().statusCode(200);
    }
}

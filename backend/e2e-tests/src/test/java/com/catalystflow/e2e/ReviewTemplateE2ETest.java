package com.catalystflow.e2e;

import com.catalystflow.e2e.support.E2EExtension;
import com.catalystflow.e2e.support.ReviewTestHelpers;
import com.catalystflow.e2e.support.TestFixtures;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * E2E tests for review template CRUD and RBAC.
 *
 * Covers (P1 — foundation for cycles and reviews):
 *   POST   /templates                    — create (ADMIN only)
 *   GET    /templates                    — list (ADMIN only) + reviewType filter
 *   GET    /templates/{id}               — read (any authenticated user)
 *   PUT    /templates/{id}               — update (ADMIN only)
 *   DELETE /templates/{id}               — delete (ADMIN only)
 *   GET    /templates/check-name         — name availability (ADMIN only)
 *
 * RBAC matrix derived from {@code ReviewTemplateResource}. Templates that are
 * persisted as part of a test register themselves for teardown so the run does
 * not leak rows.
 */
@ExtendWith(E2EExtension.class)
@DisplayName("Review Template E2E")
class ReviewTemplateE2ETest {

    private static TestFixtures fx;

    @BeforeAll
    static void init(org.junit.jupiter.api.extension.ExtensionContext ctx) {
        fx = E2EExtension.fixtures(ctx);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /templates
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /templates")
    class CreateTemplate {

        @Test
        @DisplayName("ADMIN → 201 with templateId")
        void admin_returns201() {
            String templateId = ReviewTestHelpers.createTemplate(fx, fx.admin.token(), "SELF");
            // createTemplate already asserts 201 + non-null id and registers cleanup
            // — this test simply verifies the helper succeeds against the live API.
            org.junit.jupiter.api.Assertions.assertNotNull(templateId);
        }

        @Test
        @DisplayName("MANAGER → 403")
        void manager_returns403() {
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(minimalCreateBody("SELF"))
            .when()
                .post("/templates")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("EMPLOYEE → 403")
        void employee_returns403() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .contentType(ContentType.JSON)
                .body(minimalCreateBody("SELF"))
            .when()
                .post("/templates")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("ADMIN → duplicate template name → 400")
        void admin_duplicateName_returns400() {
            // Create a template with a deterministic name, then try again — should clash.
            String name = "Dup E2E " + UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> body = minimalCreateBody("SELF");
            body.put("templateName", name);

            String firstId = given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/templates")
            .then()
                .statusCode(201)
                .extract().path("templateId");
            fx.registerTemplateForCleanup(firstId);

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/templates")
            .then()
                .statusCode(400);
        }

        @Test
        @DisplayName("ADMIN → empty questions → 400")
        void admin_emptyQuestions_returns400() {
            Map<String, Object> body = new HashMap<>();
            body.put("templateName", "Empty E2E " + UUID.randomUUID());
            body.put("reviewType", "SELF");
            body.put("questions", List.of()); // violates minItems: 1

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/templates")
            .then()
                .statusCode(400);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /templates
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /templates")
    class ListTemplates {

        @Test
        @DisplayName("ADMIN → 200 with templates array")
        void admin_returns200() {
            String templateId = ReviewTestHelpers.createTemplate(fx, fx.admin.token(), "SELF");

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
            .when()
                .get("/templates")
            .then()
                .statusCode(200)
                .body("templates", notNullValue())
                .body("templates.templateId", hasItem(templateId))
                .body("count", greaterThanOrEqualTo(1));
        }

        @Test
        @DisplayName("ADMIN + reviewType=MANAGER → 200 filtered")
        void admin_filterByReviewType_returns200() {
            String mgrTemplateId = ReviewTestHelpers.createTemplate(fx, fx.admin.token(), "MANAGER");

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .queryParam("reviewType", "MANAGER")
            .when()
                .get("/templates")
            .then()
                .statusCode(200)
                .body("templates.reviewType", everyItem(equalTo("MANAGER")))
                .body("templates.templateId", hasItem(mgrTemplateId));
        }

        @Test
        @DisplayName("MANAGER → 403")
        void manager_returns403() {
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
            .when()
                .get("/templates")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("EMPLOYEE → 403")
        void employee_returns403() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/templates")
            .then()
                .statusCode(403);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /templates/{id}
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /templates/{id}")
    class GetTemplate {

        @Test
        @DisplayName("EMPLOYEE → existing template → 200 (any authenticated user)")
        void employee_existing_returns200() {
            String templateId = ReviewTestHelpers.createTemplate(fx, fx.admin.token(), "SELF");

            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/templates/" + templateId)
            .then()
                .statusCode(200)
                .body("templateId", equalTo(templateId))
                .body("reviewType", equalTo("SELF"));
        }

        @Test
        @DisplayName("ADMIN → non-existent template → 404")
        void admin_nonExistent_returns404() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
            .when()
                .get("/templates/00000000-0000-0000-0000-000000000000")
            .then()
                .statusCode(404);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUT /templates/{id}
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("PUT /templates/{id}")
    class UpdateTemplate {

        @Test
        @DisplayName("ADMIN → 200 + name persisted")
        void admin_updateName_returns200() {
            String templateId = ReviewTestHelpers.createTemplate(fx, fx.admin.token(), "SELF");

            Map<String, Object> body = new HashMap<>();
            body.put("templateName", "Renamed E2E " + UUID.randomUUID().toString().substring(0, 8));

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .put("/templates/" + templateId)
            .then()
                .statusCode(200)
                .body("templateName", equalTo(body.get("templateName")));
        }

        @Test
        @DisplayName("MANAGER → 403")
        void manager_returns403() {
            String templateId = ReviewTestHelpers.createTemplate(fx, fx.admin.token(), "SELF");

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(Map.of("templateName", "Should Not Stick"))
            .when()
                .put("/templates/" + templateId)
            .then()
                .statusCode(403);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE /templates/{id}
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /templates/{id}")
    class DeleteTemplate {

        @Test
        @DisplayName("ADMIN → 204 + GET returns 404 after delete")
        void admin_returns204() {
            String templateId = ReviewTestHelpers.createTemplate(fx, fx.admin.token(), "SELF");

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
            .when()
                .delete("/templates/" + templateId)
            .then()
                .statusCode(204);

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
            .when()
                .get("/templates/" + templateId)
            .then()
                .statusCode(404);
        }

        @Test
        @DisplayName("MANAGER → 403")
        void manager_returns403() {
            String templateId = ReviewTestHelpers.createTemplate(fx, fx.admin.token(), "SELF");

            given()
                .header("Authorization", "Bearer " + fx.manager.token())
            .when()
                .delete("/templates/" + templateId)
            .then()
                .statusCode(403);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /templates/check-name
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /templates/check-name")
    class CheckName {

        @Test
        @DisplayName("ADMIN → fresh name → available:true")
        void admin_freshName_returnsAvailable() {
            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .queryParam("name", "Pristine " + UUID.randomUUID())
            .when()
                .get("/templates/check-name")
            .then()
                .statusCode(200)
                .body("available", equalTo(true));
        }

        @Test
        @DisplayName("ADMIN → taken name → available:false")
        void admin_takenName_returnsUnavailable() {
            String name = "Taken " + UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> body = minimalCreateBody("SELF");
            body.put("templateName", name);
            String templateId = given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/templates")
            .then()
                .statusCode(201)
                .extract().path("templateId");
            fx.registerTemplateForCleanup(templateId);

            given()
                .header("Authorization", "Bearer " + fx.admin.token())
                .queryParam("name", name)
            .when()
                .get("/templates/check-name")
            .then()
                .statusCode(200)
                .body("available", equalTo(false));
        }

        @Test
        @DisplayName("MANAGER → 403")
        void manager_returns403() {
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .queryParam("name", "Anything")
            .when()
                .get("/templates/check-name")
            .then()
                .statusCode(403);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Map<String, Object> minimalCreateBody(String reviewType) {
        Map<String, Object> question = new HashMap<>();
        question.put("questionId", UUID.randomUUID().toString());
        question.put("text", "How did the employee perform?");
        question.put("type", "TEXT");
        question.put("required", true);
        question.put("order", 1);

        Map<String, Object> body = new HashMap<>();
        body.put("templateName", "RBAC " + UUID.randomUUID());
        body.put("reviewType", reviewType);
        body.put("questions", List.of(question));
        return body;
    }
}

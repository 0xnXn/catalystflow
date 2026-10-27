package com.catalystflow.e2e;

import com.catalystflow.e2e.support.E2EExtension;
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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * E2E smoke tests for the async AI endpoints.
 *
 * Every AI POST handler is fire-and-forget (returns 202 + jobId after enqueuing
 * an SQS message). Waiting for the worker to finish would make this stage slow
 * and flaky; instead we assert:
 *   - POST accepted with the expected envelope (202, jobId, status: PENDING)
 *   - GET status returns 200 and a recognised status enum value
 *   - validation: missing required fields → 400
 *   - auth: missing token → 401
 *   - RBAC: employee on manager-only endpoints → 403
 *
 * The terminal-state contract (eventually COMPLETED or FAILED) is covered by
 * unit tests in {@code ai-service/src/test} rather than here.
 */
@ExtendWith(E2EExtension.class)
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "ASSISTANCE_ENABLED", matches = "true")
@DisplayName("AI Review (async) E2E")
class AiReviewE2ETest {

    private static TestFixtures fx;

    @BeforeAll
    static void init(org.junit.jupiter.api.extension.ExtensionContext ctx) {
        fx = E2EExtension.fixtures(ctx);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // /ai-review/disciplines
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /ai-review/disciplines")
    class Disciplines {

        @Test
        @DisplayName("Authenticated → 200 with array")
        void authenticated_returns200() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/ai-review/disciplines")
            .then()
                .statusCode(200)
                .body("$", instanceOf(List.class));
        }

        @Test
        @DisplayName("No token → 401")
        void noToken_returns401() {
            given()
            .when()
                .get("/ai-review/disciplines")
            .then()
                .statusCode(401);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // /ai-review/analyze
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /ai-review/analyze")
    class AnalyzeEndpoint {

        @Test
        @DisplayName("Valid request → 202 with jobId + PENDING")
        void valid_returns202() {
            String jobId = given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .contentType(ContentType.JSON)
                .body(validAnalyzeBody())
            .when()
                .post("/ai-review/analyze")
            .then()
                .statusCode(202)
                .body("jobId",  notNullValue())
                .body("status", anyOf(equalTo("PENDING"), equalTo("PROCESSING")))
                .extract().path("jobId");

            // Status endpoint should at least acknowledge the freshly enqueued job
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/ai-review/analyze/" + jobId + "/status")
            .then()
                .statusCode(200)
                .body("jobId",  equalTo(jobId))
                .body("status", in(List.of("PENDING", "PROCESSING", "COMPLETED", "FAILED")));
        }

        @Test
        @DisplayName("Missing seniority → 400")
        void missingSeniority_returns400() {
            Map<String, Object> body = validAnalyzeBody();
            body.remove("seniority");

            given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/ai-review/analyze")
            .then()
                .statusCode(400);
        }

        @Test
        @DisplayName("Empty questions → 400")
        void emptyQuestions_returns400() {
            Map<String, Object> body = validAnalyzeBody();
            body.put("questions", List.of());

            given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/ai-review/analyze")
            .then()
                .statusCode(400);
        }

        @Test
        @DisplayName("No token → 401")
        void noToken_returns401() {
            given()
                .contentType(ContentType.JSON)
                .body(validAnalyzeBody())
            .when()
                .post("/ai-review/analyze")
            .then()
                .statusCode(401);
        }

        @Test
        @DisplayName("Non-existent jobId on status → 404")
        void status_nonExistentJob_returns404() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/ai-review/analyze/00000000-0000-0000-0000-000000000000/status")
            .then()
                .statusCode(404);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // /ai-review/insights
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /ai-review/insights")
    class InsightsEndpoint {

        @Test
        @DisplayName("Valid request → 202 + status endpoint reachable")
        void valid_returns202() {
            String jobId = given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .contentType(ContentType.JSON)
                .body(validAnalyzeBody())
            .when()
                .post("/ai-review/insights")
            .then()
                .statusCode(202)
                .body("jobId", notNullValue())
                .extract().path("jobId");

            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/ai-review/insights/" + jobId + "/status")
            .then()
                .statusCode(200)
                .body("status", in(List.of("PENDING", "PROCESSING", "COMPLETED", "FAILED")));
        }

        @Test
        @DisplayName("Missing discipline → 400")
        void missingDiscipline_returns400() {
            Map<String, Object> body = validAnalyzeBody();
            body.remove("discipline");

            given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .contentType(ContentType.JSON)
                .body(body)
            .when()
                .post("/ai-review/insights")
            .then()
                .statusCode(400);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Manager flows — admin/manager only
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Manager AI endpoints — RBAC")
    class ManagerRbac {

        @Test
        @DisplayName("EMPLOYEE → manager golden → 403")
        void employee_managerGolden_returns403() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .contentType(ContentType.JSON)
            .when()
                .post("/ai-review/manager/reviews/any-review-id/golden")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("EMPLOYEE → manager jobs status → 403")
        void employee_managerJobStatus_returns403() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
            .when()
                .get("/ai-review/manager/jobs/00000000-0000-0000-0000-000000000000/status")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("EMPLOYEE → peer360-digest → 403")
        void employee_peer360Digest_returns403() {
            given()
                .header("Authorization", "Bearer " + fx.employee.token())
                .contentType(ContentType.JSON)
            .when()
                .post("/ai-review/manager/cycles/any-cycle/subjects/" + fx.employee.userId() + "/peer360-digest")
            .then()
                .statusCode(403);
        }

        @Test
        @DisplayName("MANAGER → question-suggest with missing body fields → 400")
        void manager_questionSuggest_missingFields_returns400() {
            // Empty body should fail all the required-field checks in the resource
            given()
                .header("Authorization", "Bearer " + fx.manager.token())
                .contentType(ContentType.JSON)
                .body(new HashMap<>())
            .when()
                .post("/ai-review/manager/reviews/any-review-id/question-suggest")
            .then()
                .statusCode(400);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Map<String, Object> validAnalyzeBody() {
        Map<String, Object> q = new HashMap<>();
        q.put("questionId",   "q-1");
        q.put("questionText", "Describe your contributions this quarter.");
        q.put("answer",       "Led the rollout of feature X and mentored two juniors.");

        Map<String, Object> body = new HashMap<>();
        body.put("questions",  List.of(q));
        body.put("seniority",  "Senior Engineer");
        body.put("discipline", "software-devops-engineering");
        return body;
    }
}

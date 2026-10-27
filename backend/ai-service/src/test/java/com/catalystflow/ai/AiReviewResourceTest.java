package com.catalystflow.ai;

import com.catalystflow.ai.model.AiReviewJobResponse;
import com.catalystflow.ai.model.AiReviewJobStatus;
import com.catalystflow.ai.model.AiReviewResponse;
import com.catalystflow.ai.model.AiReviewStatusResponse;
import com.catalystflow.ai.model.QuestionSuggestRequest;
import com.catalystflow.ai.model.ManagerGoldenReview;
import com.catalystflow.ai.service.AiReviewService;
import com.catalystflow.ai.service.JobNotCompletedException;
import io.quarkus.test.InjectMock;
import jakarta.ws.rs.BadRequestException;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@QuarkusTest
class AiReviewResourceTest {

    @InjectMock
    AiReviewService aiReviewService;

    @InjectMock
    ClaimsContext claimsContext;

    @BeforeEach
    void setUp() {
        reset(aiReviewService);
        when(claimsContext.getUserId()).thenReturn("employee-id");
    }

    @Test
    void analyze_validRequest_returns202WithJobId() {
        AiReviewJobResponse job = new AiReviewJobResponse();
        job.setJobId("job-1");
        job.setStatus(AiReviewJobStatus.PENDING);
        when(aiReviewService.startAnalysis(any())).thenReturn(job);

        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "questions", List.of(Map.of(
                                "questionId", "q1",
                                "questionText", "Describe your technical contributions.",
                                "answer", "I improved API reliability."
                        )),
                        "seniority", "Engineer",
                        "discipline", "Software & DevOps Engineering"
                ))
                .when().post("/ai-review/analyze")
                .then()
                .statusCode(202)
                .body("jobId", equalTo("job-1"))
                .body("status", equalTo("PENDING"));
    }

    @Test
    void latestPeer360Digest_completedExists_returns200WithJobId() {
        when(claimsContext.isManager()).thenReturn(true);
        when(aiReviewService.latestCompletedPeer360Digest("cyc-1", "subj-1")).thenReturn("digest-123");
        given()
                .when().get("/ai-review/manager/cycles/cyc-1/subjects/subj-1/peer360-digest/latest")
                .then()
                .statusCode(200)
                .body("jobId", equalTo("digest-123"));
    }

    @Test
    void latestPeer360Digest_none_returns204() {
        when(claimsContext.isManager()).thenReturn(true);
        when(aiReviewService.latestCompletedPeer360Digest("cyc-1", "subj-1")).thenReturn(null);
        given()
                .when().get("/ai-review/manager/cycles/cyc-1/subjects/subj-1/peer360-digest/latest")
                .then()
                .statusCode(204);
    }

    @Test
    void latestPeer360Digest_notManagerOrAdmin_returns403() {
        // default setUp: an employee (no manager/admin role) — gated out
        given()
                .when().get("/ai-review/manager/cycles/cyc-1/subjects/subj-1/peer360-digest/latest")
                .then()
                .statusCode(403);
    }

    @Test
    void analyze_missingQuestions_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "questions", List.of(),
                        "seniority", "Engineer",
                        "discipline", "Software & DevOps Engineering"
                ))
                .when().post("/ai-review/analyze")
                .then()
                .statusCode(400);

        verify(aiReviewService, never()).startAnalysis(any());
    }

    @Test
    void analyze_missingSeniority_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "questions", List.of(Map.of(
                                "questionId", "q1",
                                "questionText", "Q",
                                "answer", "A"
                        )),
                        "discipline", "Software & DevOps Engineering"
                ))
                .when().post("/ai-review/analyze")
                .then()
                .statusCode(400);
    }

    @Test
    void status_existingJob_returnsStatusAndResult() {
        AiReviewStatusResponse status = new AiReviewStatusResponse();
        status.setJobId("job-2");
        status.setStatus(AiReviewJobStatus.COMPLETED);
        AiReviewResponse result = new AiReviewResponse();
        status.setResult(result);
        when(aiReviewService.getAnalysisStatus("job-2")).thenReturn(status);

        given()
                .when().get("/ai-review/analyze/job-2/status")
                .then()
                .statusCode(200)
                .body("jobId", equalTo("job-2"))
                .body("status", equalTo("COMPLETED"));
    }

    @Test
    void status_missingJob_returns404() {
        when(aiReviewService.getAnalysisStatus("missing")).thenReturn(null);

        given()
                .when().get("/ai-review/analyze/missing/status")
                .then()
                .statusCode(404);
    }

    @Test
    void status_pendingJob_returnsPending() {
        AiReviewStatusResponse status = new AiReviewStatusResponse();
        status.setJobId("job-3");
        status.setStatus(AiReviewJobStatus.PENDING);
        when(aiReviewService.getAnalysisStatus("job-3")).thenReturn(status);

        given()
                .when().get("/ai-review/analyze/job-3/status")
                .then()
                .statusCode(200)
                .body("status", equalTo("PENDING"));
    }

    // ── Manager endpoint tests (G3 — async via SQS) ──────────────────────────

    @Test
    void managerGolden_asManager_returns202WithJobId() {
        when(claimsContext.isManager()).thenReturn(true);
        AiReviewJobResponse job = new AiReviewJobResponse();
        job.setJobId("uuid-golden-1");
        job.setStatus(AiReviewJobStatus.PENDING);
        when(aiReviewService.startManagerGolden(eq("rev-2"), anyString())).thenReturn(job);

        given()
                .contentType(ContentType.JSON)
                .when().post("/ai-review/manager/reviews/rev-2/golden")
                .then()
                .statusCode(202)
                .body("jobId", equalTo("uuid-golden-1"))
                .body("status", equalTo("PENDING"));
    }

    @Test
    void managerGolden_asEmployee_returns403() {
        given()
                .contentType(ContentType.JSON)
                .when().post("/ai-review/manager/reviews/rev-2/golden")
                .then()
                .statusCode(403);

        verify(aiReviewService, never()).startManagerGolden(anyString(), anyString());
    }

    @Test
    void managerQuestionSuggest_validRequest_returns202WithJobId() {
        when(claimsContext.isManager()).thenReturn(true);
        AiReviewJobResponse job = new AiReviewJobResponse();
        job.setJobId("uuid-suggest-q1");
        job.setStatus(AiReviewJobStatus.PENDING);
        when(aiReviewService.startManagerQuestionSuggest(eq("rev-3"), any())).thenReturn(job);

        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "questionId", "q1",
                        "questionText", "Describe your client communication approach.",
                        "managerCurrentAnswer", "I managed stakeholder expectations.",
                        "discipline", "Software & DevOps Engineering",
                        "seniority", "Engineer",
                        "employeeReview", Map.of(
                                "answers", java.util.List.of(
                                        Map.of(
                                                "questionId", "q1",
                                                "questionText", "Describe your communication",
                                                "response", "I communicate well."
                                        )
                                )
                        )
                ))
                .when().post("/ai-review/manager/reviews/rev-3/question-suggest")
                .then()
                .statusCode(202)
                .body("jobId", equalTo("uuid-suggest-q1"));
    }

    @Test
    void managerQuestionSuggest_missingQuestionText_returns400() {
        when(claimsContext.isManager()).thenReturn(true);

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("questionId", "q1"))
                .when().post("/ai-review/manager/reviews/rev-3/question-suggest")
                .then()
                .statusCode(400);

        verify(aiReviewService, never()).startManagerQuestionSuggest(anyString(), any());
    }

    @Test
    void managerQuestionSuggest_missingQuestionId_returns400() {
        when(claimsContext.isManager()).thenReturn(true);

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("questionText", "Describe a recent win"))
                .when().post("/ai-review/manager/reviews/rev-3/question-suggest")
                .then()
                .statusCode(400);

        verify(aiReviewService, never()).startManagerQuestionSuggest(anyString(), any());
    }

    // ── Manager job-status endpoint — the loose-typed poll target ────────────
    // Critical regression guard: manager flows must NOT poll /analyze/{jobId}/status,
    // because that endpoint binds results into AiReviewResponse and silently drops
    // unknown fields (the bug this endpoint was created to fix).

    @Test
    void getManagerJobStatus_managerCaller_returnsLooseTypedBody() {
        when(claimsContext.isManager()).thenReturn(true);
        // Caller (setUp: "employee-id") owns the job they're reading.
        when(aiReviewService.getJobOwner("uuid-mg-1")).thenReturn("employee-id");

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("jobId", "uuid-mg-1");
        body.put("status", "COMPLETED");
        body.put("result", Map.of(
                "missingAreas", List.of(),
                "thinAreas",    List.of("CONSULTING: tighten anecdote"),
                "wellCoveredAreas", List.of("TECHNOLOGY"),
                "overallGuidance", "Strong overall."
        ));
        when(aiReviewService.getJobStatus("uuid-mg-1")).thenReturn(body);

        given()
                .when().get("/ai-review/manager/jobs/uuid-mg-1/status")
                .then()
                .statusCode(200)
                .body("jobId", equalTo("uuid-mg-1"))
                .body("status", equalTo("COMPLETED"))
                // Manager-specific result fields MUST round-trip — they would not
                // if the endpoint bound into AiReviewResponse.
                .body("result.overallGuidance", equalTo("Strong overall."))
                .body("result.thinAreas[0]", equalTo("CONSULTING: tighten anecdote"));
    }

    @Test
    void getManagerJobStatus_employeeCaller_returns403() {
        given()
                .when().get("/ai-review/manager/jobs/uuid-mg-2/status")
                .then()
                .statusCode(403);
    }

    @Test
    void getManagerJobStatus_missingJob_returns404() {
        when(claimsContext.isManager()).thenReturn(true);
        when(aiReviewService.getJobStatus("ghost")).thenReturn(null);

        given()
                .when().get("/ai-review/manager/jobs/ghost/status")
                .then()
                .statusCode(404);
    }

    // ── Ownership scoping on the manager-job endpoints (NEW-1) ───────────────
    // Role alone is not enough: a manager may only read/write a job they
    // requested. A non-owner gets 404 (not 403) so they can't distinguish
    // "exists but not yours" from "missing" and enumerate jobIds. Admins bypass.

    @Test
    void getManagerJobStatus_nonOwnerManager_returns404() {
        when(claimsContext.isManager()).thenReturn(true);
        when(aiReviewService.getJobOwner("uuid-mg-9")).thenReturn("another-manager");
        // The job exists — getJobStatus would return a body. The only thing
        // stopping the caller is that they don't own it.
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("jobId", "uuid-mg-9");
        body.put("status", "COMPLETED");
        lenient().when(aiReviewService.getJobStatus("uuid-mg-9")).thenReturn(body);

        given()
                .when().get("/ai-review/manager/jobs/uuid-mg-9/status")
                .then()
                .statusCode(404);

        // Must short-circuit before fetching the (other manager's) content.
        verify(aiReviewService, never()).getJobStatus("uuid-mg-9");
    }

    @Test
    void getManagerJobStatus_adminBypassesOwnership_returns200() {
        when(claimsContext.isAdmin()).thenReturn(true);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("jobId", "uuid-mg-1");
        body.put("status", "COMPLETED");
        when(aiReviewService.getJobStatus("uuid-mg-1")).thenReturn(body);

        given()
                .when().get("/ai-review/manager/jobs/uuid-mg-1/status")
                .then()
                .statusCode(200)
                .body("jobId", equalTo("uuid-mg-1"));

        // Admin short-circuits — no ownership lookup needed.
        verify(aiReviewService, never()).getJobOwner(anyString());
    }

    // ── Manager-job result PUT: ownership + 409-vs-400 (NEW-1 + NEW-5) ───────

    @Test
    void updateManagerJobResult_owner_returns200() {
        when(claimsContext.isManager()).thenReturn(true);
        when(aiReviewService.getJobOwner("uuid-mg-1")).thenReturn("employee-id");
        when(aiReviewService.updateManagerGoldenResult(eq("uuid-mg-1"), any()))
                .thenReturn(new ManagerGoldenReview());

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("overallGuidance", "Solid work this cycle."))
                .when().put("/ai-review/manager/jobs/uuid-mg-1/result")
                .then()
                .statusCode(200);
    }

    @Test
    void updateManagerJobResult_nonOwner_returns404() {
        when(claimsContext.isManager()).thenReturn(true);
        when(aiReviewService.getJobOwner("uuid-mg-9")).thenReturn("another-manager");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("overallGuidance", "I should not be able to overwrite this."))
                .when().put("/ai-review/manager/jobs/uuid-mg-9/result")
                .then()
                .statusCode(404);

        // The write must never reach the service for a non-owner.
        verify(aiReviewService, never()).updateManagerGoldenResult(anyString(), any());
    }

    @Test
    void updateManagerJobResult_employeeCaller_returns403() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("overallGuidance", "x"))
                .when().put("/ai-review/manager/jobs/uuid-mg-1/result")
                .then()
                .statusCode(403);

        verify(aiReviewService, never()).updateManagerGoldenResult(anyString(), any());
    }

    @Test
    void updateManagerJobResult_notCompletedRow_returns409() {
        when(claimsContext.isManager()).thenReturn(true);
        when(aiReviewService.getJobOwner("uuid-mg-1")).thenReturn("employee-id");
        when(aiReviewService.updateManagerGoldenResult(eq("uuid-mg-1"), any()))
                .thenThrow(new JobNotCompletedException("Job result can only be edited once it has COMPLETED"));

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("overallGuidance", "editing before the row is COMPLETED"))
                .when().put("/ai-review/manager/jobs/uuid-mg-1/result")
                .then()
                .statusCode(409);
    }

    @Test
    void updateManagerJobResult_badShape_returns400() {
        when(claimsContext.isManager()).thenReturn(true);
        when(aiReviewService.getJobOwner("uuid-mg-1")).thenReturn("employee-id");
        when(aiReviewService.updateManagerGoldenResult(eq("uuid-mg-1"), any()))
                .thenThrow(new BadRequestException("Body does not match ManagerGoldenReview shape"));

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("overallGuidance", "malformed edit"))
                .when().put("/ai-review/manager/jobs/uuid-mg-1/result")
                .then()
                .statusCode(400);
    }
}

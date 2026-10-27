package com.catalystflow.review;

import com.catalystflow.review.model.ReviewCycle;
import com.catalystflow.review.service.ReviewCycleService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REST-layer tests for {@link ReviewCycleResource}. Verifies auth gates,
 * status codes, and error-mapping across the cycle lifecycle endpoints.
 */
@QuarkusTest
class ReviewCycleResourceTest {

    @InjectMock
    ReviewCycleService cycleService;

    @InjectMock
    ClaimsContext claimsContext;

    @BeforeEach
    void setUp() {
        reset(cycleService);
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

    private ReviewCycle sample(String cycleId, ReviewCycle.StatusEnum status) {
        ReviewCycle c = new ReviewCycle();
        c.setCycleId(cycleId);
        c.setCycleTitle("Q3 Cycle");
        c.setStatus(status);
        c.setStartDate("01/07/2026");
        c.setDueDate("30/09/2026");
        return c;
    }

    // ── health ───────────────────────────────────────────────────────────────

    @Test
    void health_returns200() {
        given()
            .when().get("/cycles/health")
            .then()
            .statusCode(200)
            .body("message", containsString("Review Cycle Service is Online"));
    }

    // ── sync (called by EventBridge scheduler, no auth needed) ───────────────

    @Test
    void sync_callsServiceWithToday_returns200() {
        given()
            .contentType(ContentType.JSON)
            .when().post("/cycles/sync")
            .then()
            .statusCode(200)
            .body("message", containsString("Sync complete"));

        verify(cycleService).syncCycleStatuses(any(LocalDate.class));
    }

    @Test
    void sync_serviceFailure_returns500() {
        doThrow(new RuntimeException("Dynamo timeout"))
                .when(cycleService).syncCycleStatuses(any(LocalDate.class));

        given()
            .contentType(ContentType.JSON)
            .when().post("/cycles/sync")
            .then()
            .statusCode(500)
            .body("message", containsString("Sync failed"));
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    void create_happy_returns201() {
        asAdmin();
        when(cycleService.createCycle(any())).thenReturn(sample("c1", ReviewCycle.StatusEnum.DRAFT));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "cycleTitle", "Q3 Cycle",
                "startDate",  "01/07/2026",
                "dueDate",    "30/09/2026"))
            .when().post("/cycles")
            .then()
            .statusCode(201)
            .body("cycleId", equalTo("c1"))
            .body("status", equalTo("DRAFT"));
    }

    @Test
    void create_validationFailure_returns400() {
        asAdmin();
        when(cycleService.createCycle(any()))
                .thenThrow(new IllegalArgumentException("Invalid date order"));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("cycleTitle", "Bad"))
            .when().post("/cycles")
            .then()
            .statusCode(400)
            .body("message", containsString("Invalid date order"));
    }

    @Test
    void create_unexpectedFailure_returns500() {
        asAdmin();
        when(cycleService.createCycle(any())).thenThrow(new RuntimeException("DynamoDB down"));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("cycleTitle", "x"))
            .when().post("/cycles")
            .then().statusCode(500);
    }

    @Test
    void create_nullBody_handledGracefully() {
        asAdmin();
        when(cycleService.createCycle(any())).thenReturn(sample("c1", ReviewCycle.StatusEnum.DRAFT));

        given()
            .contentType(ContentType.JSON)
            .when().post("/cycles")
            .then()
            .statusCode(201);
    }

    // ── publish ──────────────────────────────────────────────────────────────

    @Test
    void publish_found_returns200() {
        asAdmin();
        when(cycleService.publishCycle("c1"))
                .thenReturn(Optional.of(sample("c1", ReviewCycle.StatusEnum.SCHEDULED)));

        given()
            .contentType(ContentType.JSON)
            .when().post("/cycles/c1/publish")
            .then()
            .statusCode(200)
            .body("status", equalTo("SCHEDULED"));
    }

    @Test
    void publish_notFound_returns404() {
        asAdmin();
        when(cycleService.publishCycle("missing")).thenReturn(Optional.empty());

        given()
            .contentType(ContentType.JSON)
            .when().post("/cycles/missing/publish")
            .then().statusCode(404);
    }

    @Test
    void publish_invalidState_returns400() {
        asAdmin();
        when(cycleService.publishCycle("c1"))
                .thenThrow(new IllegalStateException("Cycle is not DRAFT"));

        given()
            .contentType(ContentType.JSON)
            .when().post("/cycles/c1/publish")
            .then()
            .statusCode(400)
            .body("message", containsString("not DRAFT"));
    }

    @Test
    void publish_validationFailure_returns400() {
        asAdmin();
        when(cycleService.publishCycle("c1"))
                .thenThrow(new IllegalArgumentException("Missing required fields"));

        given()
            .contentType(ContentType.JSON)
            .when().post("/cycles/c1/publish")
            .then().statusCode(400);
    }

    // ── activate ─────────────────────────────────────────────────────────────

    @Test
    void activate_found_returns200() {
        asAdmin();
        when(cycleService.activateCycle("c1"))
                .thenReturn(Optional.of(sample("c1", ReviewCycle.StatusEnum.ACTIVE)));

        given()
            .contentType(ContentType.JSON)
            .when().post("/cycles/c1/activate")
            .then()
            .statusCode(200)
            .body("status", equalTo("ACTIVE"));
    }

    @Test
    void activate_notFound_returns404() {
        asAdmin();
        when(cycleService.activateCycle("missing")).thenReturn(Optional.empty());

        given()
            .contentType(ContentType.JSON)
            .when().post("/cycles/missing/activate")
            .then().statusCode(404);
    }

    @Test
    void activate_invalidState_returns400() {
        asAdmin();
        when(cycleService.activateCycle("c1"))
                .thenThrow(new IllegalStateException("Cycle not SCHEDULED"));

        given()
            .contentType(ContentType.JSON)
            .when().post("/cycles/c1/activate")
            .then()
            .statusCode(400)
            .body("message", containsString("not SCHEDULED"));
    }

    // ── PRE-8: role gates on create / publish / activate ─────────────────────

    @Test
    void create_nonAdmin_returns403() {
        asEmployee();
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("cycleTitle", "x", "templateId", "t1",
                    "startDate", "01/07/2026", "dueDate", "30/09/2026"))
            .when().post("/cycles")
            .then().statusCode(403);
        verify(cycleService, never()).createCycle(any());
    }

    @Test
    void publish_nonAdmin_returns403() {
        asEmployee();
        given()
            .contentType(ContentType.JSON)
            .when().post("/cycles/c1/publish")
            .then().statusCode(403);
        verify(cycleService, never()).publishCycle(any());
    }

    @Test
    void activate_nonAdmin_returns403() {
        asEmployee();
        given()
            .contentType(ContentType.JSON)
            .when().post("/cycles/c1/activate")
            .then().statusCode(403);
        verify(cycleService, never()).activateCycle(any());
    }

    // ── complete ─────────────────────────────────────────────────────────────

    @Test
    void complete_nonAdmin_returns403() {
        asEmployee();
        given()
            .contentType(ContentType.JSON)
            .when().post("/cycles/c1/complete")
            .then().statusCode(403);
        verify(cycleService, never()).completeCycle(any());
    }

    @Test
    void complete_admin_found_returns200() {
        asAdmin();
        when(cycleService.completeCycle("c1"))
                .thenReturn(Optional.of(sample("c1", ReviewCycle.StatusEnum.COMPLETED)));

        given()
            .contentType(ContentType.JSON)
            .when().post("/cycles/c1/complete")
            .then()
            .statusCode(200)
            .body("status", equalTo("COMPLETED"));
    }

    @Test
    void complete_admin_notFound_returns404() {
        asAdmin();
        when(cycleService.completeCycle("missing")).thenReturn(Optional.empty());

        given()
            .contentType(ContentType.JSON)
            .when().post("/cycles/missing/complete")
            .then().statusCode(404);
    }

    @Test
    void complete_admin_invalidState_returns400() {
        asAdmin();
        when(cycleService.completeCycle("c1"))
                .thenThrow(new IllegalStateException("Cycle not ACTIVE"));

        given()
            .contentType(ContentType.JSON)
            .when().post("/cycles/c1/complete")
            .then()
            .statusCode(400)
            .body("message", containsString("not ACTIVE"));
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Test
    void list_noFilter_returnsAll() {
        when(cycleService.getAllCycles(null))
                .thenReturn(List.of(sample("c1", ReviewCycle.StatusEnum.ACTIVE)));

        given()
            .when().get("/cycles")
            .then().statusCode(200);
    }

    @Test
    void list_withValidFilter_passesThrough() {
        when(cycleService.getAllCycles("ACTIVE"))
                .thenReturn(List.of(sample("c1", ReviewCycle.StatusEnum.ACTIVE)));

        given()
            .queryParam("status", "ACTIVE")
            .when().get("/cycles")
            .then().statusCode(200);
        verify(cycleService).getAllCycles("ACTIVE");
    }

    @Test
    void list_invalidFilter_returns400() {
        when(cycleService.getAllCycles("BAD"))
                .thenThrow(new IllegalArgumentException("Invalid status"));

        given()
            .queryParam("status", "BAD")
            .when().get("/cycles")
            .then()
            .statusCode(400)
            .body("message", containsString("Invalid status"));
    }

    // ── get ──────────────────────────────────────────────────────────────────

    @Test
    void get_found_returns200() {
        when(cycleService.getReviewCycle("c1"))
                .thenReturn(Optional.of(sample("c1", ReviewCycle.StatusEnum.SCHEDULED)));

        given()
            .when().get("/cycles/c1")
            .then()
            .statusCode(200)
            .body("cycleId", equalTo("c1"));
    }

    @Test
    void get_notFound_returns404() {
        when(cycleService.getReviewCycle("missing")).thenReturn(Optional.empty());

        given()
            .when().get("/cycles/missing")
            .then().statusCode(404);
    }

    // ── update ───────────────────────────────────────────────────────────────

    @Test
    void update_nonAdmin_returns403() {
        asEmployee();
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("cycleTitle", "Renamed"))
            .when().put("/cycles/c1")
            .then().statusCode(403);
        verify(cycleService, never()).updateCycle(any(), any());
    }

    @Test
    void update_admin_found_returns200() {
        asAdmin();
        when(cycleService.updateCycle(eq("c1"), any()))
                .thenReturn(Optional.of(sample("c1", ReviewCycle.StatusEnum.DRAFT)));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("cycleTitle", "Renamed"))
            .when().put("/cycles/c1")
            .then().statusCode(200);
    }

    @Test
    void update_admin_notFound_returns404() {
        asAdmin();
        when(cycleService.updateCycle(any(), any())).thenReturn(Optional.empty());

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("cycleTitle", "Renamed"))
            .when().put("/cycles/missing")
            .then().statusCode(404);
    }

    @Test
    void update_admin_validationFailure_returns400() {
        asAdmin();
        when(cycleService.updateCycle(any(), any()))
                .thenThrow(new IllegalArgumentException("Invalid status transition"));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("status", "ACTIVE"))
            .when().put("/cycles/c1")
            .then()
            .statusCode(400)
            .body("message", containsString("Invalid status transition"));
    }

    @Test
    void update_admin_invalidState_returns400() {
        asAdmin();
        when(cycleService.updateCycle(any(), any()))
                .thenThrow(new IllegalStateException("Cannot edit COMPLETED cycle"));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("cycleTitle", "x"))
            .when().put("/cycles/c1")
            .then()
            .statusCode(400)
            .body("message", containsString("COMPLETED"));
    }

    @Test
    void update_admin_nullBody_handledGracefully() {
        asAdmin();
        when(cycleService.updateCycle(eq("c1"), any()))
                .thenReturn(Optional.of(sample("c1", ReviewCycle.StatusEnum.DRAFT)));

        given()
            .contentType(ContentType.JSON)
            .when().put("/cycles/c1")
            .then().statusCode(200);
    }

    @Test
    void update_admin_withStatus_mapsThroughEnum() {
        asAdmin();
        when(cycleService.updateCycle(eq("c1"), any()))
                .thenReturn(Optional.of(sample("c1", ReviewCycle.StatusEnum.SCHEDULED)));

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("status", "SCHEDULED"))
            .when().put("/cycles/c1")
            .then().statusCode(200);
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    void delete_nonAdmin_returns403() {
        asEmployee();
        given()
            .when().delete("/cycles/c1")
            .then().statusCode(403);
        verify(cycleService, never()).deleteCycle(any());
    }

    @Test
    void delete_admin_found_returns204() {
        asAdmin();
        when(cycleService.deleteCycle("c1")).thenReturn(true);

        given()
            .when().delete("/cycles/c1")
            .then().statusCode(204);
    }

    @Test
    void delete_admin_notFound_returns404() {
        asAdmin();
        when(cycleService.deleteCycle("missing")).thenReturn(false);

        given()
            .when().delete("/cycles/missing")
            .then().statusCode(404);
    }
}

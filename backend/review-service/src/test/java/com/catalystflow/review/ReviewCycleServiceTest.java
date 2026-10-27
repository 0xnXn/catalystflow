package com.catalystflow.review;

import com.catalystflow.review.model.Milestone;
import com.catalystflow.review.model.ReviewCycle;
import com.catalystflow.review.service.ReviewCycleService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
class ReviewCycleServiceTest {

    @Inject
    ReviewCycleService service;

    @InjectMock
    DynamoDbClient mockDynamoDb;

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    private Milestone buildMilestone(String name, List<String> roles, String start, String end) {
        Milestone m = new Milestone();
        m.setName(name);
        m.setAssignedRoles(roles);
        m.setStartDate(start);
        m.setEndDate(end);
        m.setStatus(Milestone.StatusEnum.NOT_STARTED);
        return m;
    }

    private AttributeValue milestoneToAttr(Milestone m) {
        Map<String, AttributeValue> map = new HashMap<>();
        if (m.getMilestoneId() != null) map.put("milestoneId", AttributeValue.builder().s(m.getMilestoneId()).build());
        if (m.getName() != null) map.put("name", AttributeValue.builder().s(m.getName()).build());
        if (m.getStartDate() != null) map.put("startDate", AttributeValue.builder().s(m.getStartDate()).build());
        if (m.getEndDate() != null) map.put("endDate", AttributeValue.builder().s(m.getEndDate()).build());
        if (m.getStatus() != null) map.put("status", AttributeValue.builder().s(m.getStatus().value()).build());
        if (m.getAssignedRoles() != null) {
            List<AttributeValue> roles = m.getAssignedRoles().stream()
                    .map(r -> AttributeValue.builder().s(r).build())
                    .toList();
            map.put("assignedRoles", AttributeValue.builder().l(roles).build());
        }
        return AttributeValue.builder().m(map).build();
    }

    private Map<String, AttributeValue> buildCycleItem(String id, String status, String start, String end) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("cycleId", AttributeValue.builder().s(id).build());
        item.put("status", AttributeValue.builder().s(status).build());
        if (start != null) item.put("startDate", AttributeValue.builder().s(start).build());
        if (end != null) item.put("dueDate", AttributeValue.builder().s(end).build());
        item.put("createdAt", AttributeValue.builder().s("2026-01-01T10:00:00").build());
        item.put("updatedAt", AttributeValue.builder().s("2026-01-01T10:00:00").build());
        return item;
    }

    // -------------------------------------------------------------------------
    // CREATE TESTS
    // -------------------------------------------------------------------------

    @Test
    void testCreateCycleBasic() {
        ReviewCycle request = new ReviewCycle();
        request.setCycleTitle("Draft Cycle");
        request.setStartDate("2026-05-01");
        request.setDueDate("2026-06-01");

        when(mockDynamoDb.putItem(any(PutItemRequest.class))).thenReturn(null);

        ReviewCycle result = service.createCycle(request);

        assertEquals(ReviewCycle.StatusEnum.DRAFT, result.getStatus());
        assertEquals("01/05/2026", result.getStartDate());
        assertEquals("01/06/2026", result.getDueDate());
        assertNotNull(result.getCycleId());
    }

    @Test
    void testCreateCycleWithMilestoneDefaulting() {
        ReviewCycle request = new ReviewCycle();
        request.setStartDate("01/05/2026");
        request.setDueDate("31/05/2026");
        
        Milestone m = new Milestone();
        m.setName("Submission");
        request.setMilestones(List.of(m));

        when(mockDynamoDb.putItem(any(PutItemRequest.class))).thenReturn(null);

        ReviewCycle result = service.createCycle(request);

        Milestone createdM = result.getMilestones().get(0);
        assertEquals("01/05/2026", createdM.getStartDate(), "Should default to cycle startDate");
        assertEquals("31/05/2026", createdM.getEndDate(), "Should default to cycle dueDate");
        assertNotNull(createdM.getMilestoneId());
    }

    @Test
    void testCreateCycleInvalidDateOrderThrows() {
        ReviewCycle request = new ReviewCycle();
        request.setStartDate("2026-06-01");
        request.setDueDate("2026-05-01");

        assertThrows(IllegalArgumentException.class, () -> service.createCycle(request));
    }

    // -------------------------------------------------------------------------
    // PUBLISH TESTS
    // -------------------------------------------------------------------------

    @Test
    void testPublishCycleSucceeds() {
        String id = "c1";
        Map<String, AttributeValue> item = buildCycleItem(id, "DRAFT", "01/05/2026", "01/06/2026");
        item.put("cycleTitle", AttributeValue.builder().s("Ready to Publish").build());
        
        Milestone m = buildMilestone("M1", List.of("ADMIN"), "01/05/2026", "15/05/2026");
        item.put("milestones", AttributeValue.builder().l(milestoneToAttr(m)).build());

        when(mockDynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(item).build());
        when(mockDynamoDb.putItem(any(PutItemRequest.class))).thenReturn(null);

        Optional<ReviewCycle> result = service.publishCycle(id);

        assertTrue(result.isPresent());
        assertEquals(ReviewCycle.StatusEnum.SCHEDULED, result.get().getStatus());
    }

    @Test
    void testPublishCycleMissingFieldsThrows() {
        String id = "c1";
        Map<String, AttributeValue> item = buildCycleItem(id, "DRAFT", "01/05/2026", "01/06/2026");
        // Missing cycleTitle and milestones

        when(mockDynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(item).build());

        assertThrows(IllegalArgumentException.class, () -> service.publishCycle(id));
    }

    @Test
    void testPublishNonDraftThrows() {
        String id = "c1";
        Map<String, AttributeValue> item = buildCycleItem(id, "SCHEDULED", "01/05/2026", "01/06/2026");

        when(mockDynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(item).build());

        assertThrows(IllegalArgumentException.class, () -> service.publishCycle(id));
    }

    @Test
    void testPublishMilestoneMissingNameThrows() {
        String id = "c1";
        Map<String, AttributeValue> item = buildCycleItem(id, "DRAFT", "01/05/2026", "01/06/2026");
        item.put("cycleTitle", AttributeValue.builder().s("T").build());
        
        Milestone m = buildMilestone("", List.of("R"), "01/05/2026", "15/05/2026");
        item.put("milestones", AttributeValue.builder().l(milestoneToAttr(m)).build());

        when(mockDynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(item).build());

        assertThrows(IllegalArgumentException.class, () -> service.publishCycle(id));
    }

    @Test
    void testPublishMilestoneMissingRolesThrows() {
        String id = "c1";
        Map<String, AttributeValue> item = buildCycleItem(id, "DRAFT", "01/05/2026", "01/06/2026");
        item.put("cycleTitle", AttributeValue.builder().s("T").build());
        
        Milestone m = buildMilestone("M1", List.of(), "01/05/2026", "15/05/2026");
        item.put("milestones", AttributeValue.builder().l(milestoneToAttr(m)).build());

        when(mockDynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(item).build());

        assertThrows(IllegalArgumentException.class, () -> service.publishCycle(id));
    }

    // -------------------------------------------------------------------------
    // UPDATE TESTS
    // -------------------------------------------------------------------------

    @Test
    void testUpdateCycleBoundarySync() {
        String id = "c1";
        String oldStart = "01/05/2026";
        String oldDue = "31/05/2026";
        
        Map<String, AttributeValue> item = buildCycleItem(id, "DRAFT", oldStart, oldDue);
        
        // M1 is tied to start, M2 is tied to due, M3 is independent
        Milestone m1 = buildMilestone("M1", List.of("R"), oldStart, "10/05/2026");
        Milestone m2 = buildMilestone("M2", List.of("R"), "20/05/2026", oldDue);
        Milestone m3 = buildMilestone("M3", List.of("R"), "10/05/2026", "20/05/2026");
        
        item.put("milestones", AttributeValue.builder().l(milestoneToAttr(m1), milestoneToAttr(m2), milestoneToAttr(m3)).build());

        when(mockDynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(item).build());
        when(mockDynamoDb.putItem(any(PutItemRequest.class))).thenReturn(null);

        ReviewCycle request = new ReviewCycle();
        request.setStartDate("05/05/2026");
        request.setDueDate("15/06/2026");

        ReviewCycle result = service.updateCycle(id, request).get();

        assertEquals("05/05/2026", result.getMilestones().get(0).getStartDate(), "M1 should follow new cycle start");
        assertEquals("15/06/2026", result.getMilestones().get(1).getEndDate(), "M2 should follow new cycle due");
        assertEquals("10/05/2026", result.getMilestones().get(2).getStartDate(), "M3 should remain unchanged");
    }

    @Test
    void testUpdateCycleAutoExpansion() {
        String id = "c1";
        Map<String, AttributeValue> item = buildCycleItem(id, "DRAFT", "10/05/2026", "20/05/2026");
        
        when(mockDynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(item).build());
        when(mockDynamoDb.putItem(any(PutItemRequest.class))).thenReturn(null);

        ReviewCycle request = new ReviewCycle();
        Milestone m = buildMilestone("Expanded", List.of("R"), "01/05/2026", "10/06/2026");
        request.setMilestones(List.of(m));

        ReviewCycle result = service.updateCycle(id, request).get();

        assertEquals("01/05/2026", result.getStartDate(), "Cycle should expand start date");
        assertEquals("10/06/2026", result.getDueDate(), "Cycle should expand due date");
    }

    @Test
    void testUpdateCompletedCycleThrows() {
        String id = "c1";
        Map<String, AttributeValue> item = buildCycleItem(id, "COMPLETED", "01/05/2026", "01/06/2026");

        when(mockDynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(item).build());

        assertThrows(IllegalStateException.class, () -> service.updateCycle(id, new ReviewCycle()));
    }

    @Test
    void testUpdateNonExistentReturnsEmpty() {
        String id = "non-existent";
        when(mockDynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().build());

        Optional<ReviewCycle> result = service.updateCycle(id, new ReviewCycle());
        assertFalse(result.isPresent());
    }

    // -------------------------------------------------------------------------
    // GET / DELETE / ACTIVATE TESTS
    // -------------------------------------------------------------------------

    @Test
    void testGetAllCyclesNoStatus() {
        when(mockDynamoDb.scan(any(ScanRequest.class))).thenReturn(ScanResponse.builder().items(List.of(new HashMap<>(), new HashMap<>())).build());

        List<ReviewCycle> result = service.getAllCycles(null);
        assertEquals(2, result.size());
    }

    @Test
    void testGetAllCyclesWithStatus() {
        when(mockDynamoDb.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder().items(List.of(new HashMap<>())).build());

        List<ReviewCycle> result = service.getAllCycles("ACTIVE");
        assertEquals(1, result.size());
    }

    @Test
    void testGetAllCyclesInvalidStatusThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.getAllCycles("BANANA"));
    }

    @Test
    void testGetReviewCycle() {
        String id = "c1";
        when(mockDynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(buildCycleItem(id, "ACTIVE", null, null)).build());

        assertTrue(service.getReviewCycle(id).isPresent());
    }

    @Test
    void testDeleteCycle() {
        String id = "c1";
        when(mockDynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(buildCycleItem(id, "DRAFT", null, null)).build());
        when(mockDynamoDb.deleteItem(any(DeleteItemRequest.class))).thenReturn(null);

        assertTrue(service.deleteCycle(id));
        verify(mockDynamoDb).deleteItem(any(DeleteItemRequest.class));
    }

    @Test
    void testActivateCycle() {
        String id = "c1";
        when(mockDynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(buildCycleItem(id, "SCHEDULED", null, null)).build());
        when(mockDynamoDb.putItem(any(PutItemRequest.class))).thenReturn(null);

        ReviewCycle result = service.activateCycle(id).get();
        assertEquals(ReviewCycle.StatusEnum.ACTIVE, result.getStatus());
    }

    @Test
    void testActivateNonScheduledThrows() {
        String id = "c1";
        when(mockDynamoDb.getItem(any(GetItemRequest.class))).thenReturn(GetItemResponse.builder().item(buildCycleItem(id, "DRAFT", null, null)).build());

        assertThrows(IllegalStateException.class, () -> service.activateCycle(id));
    }

    @Test
    void testCompleteCycleSucceeds() {
        String id = "c1";
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(buildCycleItem(id, "ACTIVE", null, null)).build());
        when(mockDynamoDb.putItem(any(PutItemRequest.class))).thenReturn(null);

        ReviewCycle result = service.completeCycle(id).get();
        assertEquals(ReviewCycle.StatusEnum.COMPLETED, result.getStatus());
    }

    @Test
    void testCompleteNonActiveThrows() {
        String id = "c1";
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(buildCycleItem(id, "SCHEDULED", null, null)).build());

        assertThrows(IllegalStateException.class, () -> service.completeCycle(id));
    }

    @Test
    void testCompleteMissingCycleReturnsEmpty() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        assertTrue(service.completeCycle("ghost").isEmpty());
    }

    // -------------------------------------------------------------------------
    // EDGE CASES & NORMALIZATION
    // -------------------------------------------------------------------------

    @Test
    void testMilestoneInvalidDateOrderThrows() {
        ReviewCycle request = new ReviewCycle();
        request.setStartDate("01/05/2026");
        request.setDueDate("31/05/2026");
        
        Milestone m = buildMilestone("M1", List.of("R"), "15/05/2026", "10/05/2026");
        request.setMilestones(List.of(m));

        assertThrows(IllegalArgumentException.class, () -> service.createCycle(request));
    }

    @Test
    void testMixedDateFormatsNormalization() {
        ReviewCycle request = new ReviewCycle();
        request.setStartDate("2026-05-01"); // ISO
        request.setDueDate("2026/06/01");  // Slashed ISO
        
        Milestone m = buildMilestone("M1", List.of("R"), "15/05/2026", "20/05/2026"); // AU
        request.setMilestones(List.of(m));

        when(mockDynamoDb.putItem(any(PutItemRequest.class))).thenReturn(null);

        ReviewCycle result = service.createCycle(request);

        assertEquals("01/05/2026", result.getStartDate());
        assertEquals("01/06/2026", result.getDueDate());
        assertEquals("15/05/2026", result.getMilestones().get(0).getStartDate());
    }

    @Test
    void testMilestoneIdGeneration() {
        ReviewCycle request = new ReviewCycle();
        request.setStartDate("01/05/2026");
        request.setMilestones(List.of(new Milestone()));

        when(mockDynamoDb.putItem(any(PutItemRequest.class))).thenReturn(null);

        ReviewCycle result = service.createCycle(request);
        assertNotNull(result.getMilestones().get(0).getMilestoneId());
    }
}

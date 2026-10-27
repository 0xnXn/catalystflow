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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
class ReviewCycleMaintenanceTest {

    @Inject
    ReviewCycleService service;

    @InjectMock
    DynamoDbClient mockDynamoDb;

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    private AttributeValue milestoneToAttr(String name, String start, String end, String status) {
        Map<String, AttributeValue> map = new HashMap<>();
        map.put("name", AttributeValue.builder().s(name).build());
        map.put("startDate", AttributeValue.builder().s(start).build());
        map.put("endDate", AttributeValue.builder().s(end).build());
        map.put("status", AttributeValue.builder().s(status).build());
        return AttributeValue.builder().m(map).build();
    }

    private Map<String, AttributeValue> buildCycleItem(String id, String status, String start, String due, List<AttributeValue> milestones) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("cycleId", AttributeValue.builder().s(id).build());
        item.put("status", AttributeValue.builder().s(status).build());
        item.put("startDate", AttributeValue.builder().s(start).build());
        item.put("dueDate", AttributeValue.builder().s(due).build());
        if (milestones != null) {
            item.put("milestones", AttributeValue.builder().l(milestones).build());
        }
        return item;
    }

    // -------------------------------------------------------------------------
    // MAINTENANCE TESTS
    // -------------------------------------------------------------------------

    @Test
    void testScheduledToActiveTransition() {
        LocalDate today = LocalDate.of(2026, 5, 1);
        String startDate = "01/05/2026"; // Today
        
        // 1. Mock "SCHEDULED" query
        Map<String, AttributeValue> cycle = buildCycleItem("c1", "SCHEDULED", startDate, "31/05/2026", null);
        QueryResponse scheduledResponse = QueryResponse.builder().items(List.of(cycle)).build();
        
        // 2. Mock "ACTIVE" query (empty)
        QueryResponse activeResponse = QueryResponse.builder().items(new ArrayList<>()).build();

        // Ensure consecutive calls return the correct mocks for each status
        when(mockDynamoDb.query(any(QueryRequest.class)))
                .thenReturn(scheduledResponse) // First call for SCHEDULED
                .thenReturn(activeResponse);    // Second call for ACTIVE

        service.syncCycleStatuses(today);

        // Verify that putItem was called with status=ACTIVE
        verify(mockDynamoDb).putItem(argThat((PutItemRequest req) -> 
            "ACTIVE".equals(req.item().get("status").s()) && "c1".equals(req.item().get("cycleId").s())
        ));
    }

    @Test
    void testActiveToCompletedTransition() {
        LocalDate today = LocalDate.of(2026, 6, 1);
        String dueDate = "31/05/2026"; // Yesterday
        
        // 1. Mock "SCHEDULED" query (empty)
        QueryResponse scheduledResponse = QueryResponse.builder().items(new ArrayList<>()).build();

        // 2. Mock "ACTIVE" query
        Map<String, AttributeValue> cycle = buildCycleItem("c1", "ACTIVE", "01/05/2026", dueDate, null);
        QueryResponse activeResponse = QueryResponse.builder().items(List.of(cycle)).build();

        when(mockDynamoDb.query(any(QueryRequest.class)))
                .thenReturn(scheduledResponse)
                .thenReturn(activeResponse);

        service.syncCycleStatuses(today);

        // Verify that putItem was called with status=COMPLETED
        verify(mockDynamoDb).putItem(argThat((PutItemRequest req) -> 
            "COMPLETED".equals(req.item().get("status").s()) && "c1".equals(req.item().get("cycleId").s())
        ));
    }

    @Test
    void testMilestoneTransitions() {
        LocalDate today = LocalDate.of(2026, 5, 15);
        
        // M1: Start today -> should OPEN
        // M2: End yesterday -> should COMPLETE
        // M3: Future -> should stay NOT_STARTED
        List<AttributeValue> milestones = List.of(
            milestoneToAttr("M1", "15/05/2026", "20/05/2026", "NOT_STARTED"),
            milestoneToAttr("M2", "01/05/2026", "14/05/2026", "OPEN"),
            milestoneToAttr("M3", "20/05/2026", "25/05/2026", "NOT_STARTED")
        );

        Map<String, AttributeValue> cycle = buildCycleItem("c1", "ACTIVE", "01/05/2026", "31/05/2026", milestones);
        
        when(mockDynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(new ArrayList<>()).build())
                .thenReturn(QueryResponse.builder().items(List.of(cycle)).build());

        service.syncCycleStatuses(today);

        verify(mockDynamoDb).putItem(argThat((PutItemRequest req) -> {
            List<AttributeValue> updatedM = req.item().get("milestones").l();
            boolean m1Open = "OPEN".equals(updatedM.get(0).m().get("status").s());
            boolean m2Completed = "COMPLETED".equals(updatedM.get(1).m().get("status").s());
            boolean m3NotStarted = "NOT_STARTED".equals(updatedM.get(2).m().get("status").s());
            return m1Open && m2Completed && m3NotStarted;
        }));
    }

    @Test
    void testIdempotencyNoChanges() {
        LocalDate today = LocalDate.of(2026, 5, 10);
        
        // Cycle is mid-run, milestone is already OPEN
        List<AttributeValue> milestones = List.of(
            milestoneToAttr("M1", "01/05/2026", "20/05/2026", "OPEN")
        );
        Map<String, AttributeValue> cycle = buildCycleItem("c1", "ACTIVE", "01/05/2026", "31/05/2026", milestones);
        
        when(mockDynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(new ArrayList<>()).build())
                .thenReturn(QueryResponse.builder().items(List.of(cycle)).build());

        service.syncCycleStatuses(today);

        // verify NO putItem was called since no statuses changed
        verify(mockDynamoDb, never()).putItem(any(PutItemRequest.class));
    }
}

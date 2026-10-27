package com.catalystflow.notification.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationDomainLookupTest {

    NotificationDomainLookup domain;
    DynamoDbClient dynamoDb;

    @BeforeEach
    void setUp() {
        domain = new NotificationDomainLookup();
        dynamoDb = mock(DynamoDbClient.class);
        domain.dynamoDb = dynamoDb;
        domain.cyclesTable = "Cycles";
        domain.usersTable = "Users";
        domain.reviewsTable = "Reviews";
    }

    @Test
    void syncCycleStatuses_updatesCyclesAndMilestonesUsingDailyMaintenanceRules() {
        when(dynamoDb.query(any(QueryRequest.class))).thenReturn(
                QueryResponse.builder().items(List.of(cycle("scheduled", "SCHEDULED",
                        "2026-05-19", "2026-05-30",
                        milestone("m1", "NOT_STARTED", "2026-05-19", "2026-05-20")))).build(),
                QueryResponse.builder().items(List.of(cycle("active", "ACTIVE",
                        "2026-05-01", "2026-05-18",
                        milestone("m2", "OPEN", "2026-05-01", "2026-05-30")))).build());

        var result = domain.syncCycleStatuses(LocalDate.parse("2026-05-19"));

        assertEquals(2, result.cyclesUpdated());
        assertEquals(2, result.milestonesUpdated());

        ArgumentCaptor<UpdateItemRequest> updates = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDb, times(2)).updateItem(updates.capture());

        UpdateItemRequest scheduledUpdate = updates.getAllValues().get(0);
        assertEquals("ACTIVE", scheduledUpdate.expressionAttributeValues().get(":status").s());
        assertEquals("OPEN", scheduledUpdate.expressionAttributeValues().get(":milestones").l().get(0).m().get("status").s());

        UpdateItemRequest activeUpdate = updates.getAllValues().get(1);
        assertEquals("COMPLETED", activeUpdate.expressionAttributeValues().get(":status").s());
        assertEquals("COMPLETED", activeUpdate.expressionAttributeValues().get(":milestones").l().get(0).m().get("status").s());
    }

    private Map<String, AttributeValue> cycle(String id, String status, String start, String due,
                                              Map<String, AttributeValue> milestone) {
        return Map.of(
                "cycleId", s(id),
                "status", s(status),
                "startDate", s(start),
                "dueDate", s(due),
                "milestones", AttributeValue.builder()
                        .l(AttributeValue.builder().m(milestone).build())
                        .build()
        );
    }

    private Map<String, AttributeValue> milestone(String id, String status, String start, String end) {
        return Map.of(
                "milestoneId", s(id),
                "name", s(id),
                "status", s(status),
                "startDate", s(start),
                "endDate", s(end),
                "assignedRoles", AttributeValue.builder().l(s("EMPLOYEE")).build()
        );
    }

    private AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }
}

package com.catalystflow.review.service;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class UserLookupTest {

    @Inject
    UserLookup userLookup;

    @InjectMock
    DynamoDbClient mockDynamoDb;

    private Map<String, AttributeValue> userItem(String id, String status, String role, String reportingTo) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("userId", AttributeValue.builder().s(id).build());
        if (status != null)      item.put("status",      AttributeValue.builder().s(status).build());
        if (role != null)        item.put("role",        AttributeValue.builder().s(role).build());
        if (reportingTo != null) item.put("reportingTo", AttributeValue.builder().s(reportingTo).build());
        return item;
    }

    @Test
    void get_nullId_returnsEmpty_andSkipsDynamoCall() {
        assertTrue(userLookup.get(null).isEmpty());
        verify(mockDynamoDb, never()).getItem(any(GetItemRequest.class));
    }

    @Test
    void get_blankId_returnsEmpty_andSkipsDynamoCall() {
        assertTrue(userLookup.get("   ").isEmpty());
        verify(mockDynamoDb, never()).getItem(any(GetItemRequest.class));
    }

    @Test
    void get_existingUser_returnsSummary() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(userItem("u1", "Active", "EMPLOYEE", "mgr-1"))
                        .build());

        Optional<UserLookup.UserSummary> result = userLookup.get("u1");

        assertTrue(result.isPresent());
        assertEquals("u1", result.get().userId());
        assertEquals("Active", result.get().status());
        assertEquals("EMPLOYEE", result.get().role());
        assertEquals("mgr-1", result.get().reportingTo());
    }

    @Test
    void get_userWithoutOptionalFields_returnsNullsForMissingAttributes() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(userItem("u1", "Active", "EMPLOYEE", null))
                        .build());

        UserLookup.UserSummary summary = userLookup.get("u1").orElseThrow();

        assertEquals("u1", summary.userId());
        assertEquals("Active", summary.status());
        assertEquals("EMPLOYEE", summary.role());
        assertEquals(null, summary.reportingTo());
    }

    @Test
    void get_notFound_returnsEmpty() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        assertTrue(userLookup.get("nobody").isEmpty());
    }

    @Test
    void existsActive_activeUser_returnsTrue() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(userItem("u1", "Active", "EMPLOYEE", null))
                        .build());

        assertTrue(userLookup.existsActive("u1"));
    }

    @Test
    void existsActive_caseInsensitiveStatus_returnsTrue() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(userItem("u1", "active", "EMPLOYEE", null))
                        .build());

        assertTrue(userLookup.existsActive("u1"));
    }

    @Test
    void existsActive_archivedUser_returnsFalse() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(userItem("u1", "Archived", "EMPLOYEE", null))
                        .build());

        assertFalse(userLookup.existsActive("u1"));
    }

    @Test
    void existsActive_missingUser_returnsFalse() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        assertFalse(userLookup.existsActive("missing"));
    }

    @Test
    void existsActive_blankId_returnsFalse_andSkipsDynamoCall() {
        assertFalse(userLookup.existsActive(""));
        verify(mockDynamoDb, never()).getItem(any(GetItemRequest.class));
    }
}

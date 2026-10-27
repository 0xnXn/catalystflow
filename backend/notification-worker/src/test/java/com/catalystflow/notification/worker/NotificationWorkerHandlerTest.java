package com.catalystflow.notification.worker;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.catalystflow.notification.service.NotificationPayloads.NotificationIntent;
import com.catalystflow.notification.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationWorkerHandlerTest {

    NotificationWorkerHandler handler;
    NotificationService notificationService;
    NotificationDeliveryService deliveryService;

    @BeforeEach
    void setUp() {
        handler = new NotificationWorkerHandler();
        notificationService = mock(NotificationService.class);
        deliveryService = mock(NotificationDeliveryService.class);
        handler.notificationService = notificationService;
        handler.deliveryService = deliveryService;
        handler.mapper = new ObjectMapper();
    }

    @Test
    void jsonMessageIsHandledAsIntent() {
        SQSEvent event = event("{\"type\":\"AUTO_DEADLINE_REMINDER_SCAN\",\"sendDate\":\"2026-05-19\"}");

        var response = handler.handleRequest(event, null);

        assertTrue(response.getBatchItemFailures().isEmpty());
        verify(notificationService).handleIntent(any(NotificationIntent.class));
        verifyNoInteractions(deliveryService);
    }

    @Test
    void plainMessageIsHandledAsDeliveryJobId() {
        SQSEvent event = event("MANUAL#2026-05-19#user-1#review-1");

        var response = handler.handleRequest(event, null);

        assertTrue(response.getBatchItemFailures().isEmpty());
        verify(deliveryService).processDeliveryJob("MANUAL#2026-05-19#user-1#review-1");
        verifyNoInteractions(notificationService);
    }

    @Test
    void retryableRuntimeFailureReturnsBatchItemFailure() {
        doThrow(new RuntimeException("temporary")).when(deliveryService).processDeliveryJob(anyString());

        var response = handler.handleRequest(event("job-1"), null);

        assertEquals(1, response.getBatchItemFailures().size());
        assertEquals("msg-1", response.getBatchItemFailures().get(0).getItemIdentifier());
    }

    private SQSEvent event(String body) {
        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setMessageId("msg-1");
        message.setBody(body);
        SQSEvent event = new SQSEvent();
        event.setRecords(List.of(message));
        return event;
    }
}

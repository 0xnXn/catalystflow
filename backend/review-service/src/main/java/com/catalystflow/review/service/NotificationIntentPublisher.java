package com.catalystflow.review.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class NotificationIntentPublisher {

    private static final Logger LOG = Logger.getLogger(NotificationIntentPublisher.class);

    @Inject
    SqsClient sqs;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "notification-events.queue-url")
    Optional<String> eventsQueueUrl;

    public void publishAutoDeadlineScan(LocalDate sendDate) {
        publish(Map.of(
                "type", "AUTO_DEADLINE_REMINDER_SCAN",
                "sendDate", sendDate.toString()
        ));
    }

    public void publishReviewPublished(String cycleId, String subjectUserId, String publishedBy) {
        publish(Map.of(
                "type", "REVIEW_PUBLISHED",
                "cycleId", cycleId,
                "subjectUserId", subjectUserId,
                "publishedBy", publishedBy == null ? "" : publishedBy
        ));
    }

    private void publish(Map<String, String> intent) {
        if (eventsQueueUrl.isEmpty()) {
            LOG.warnf("notification-events.queue-url not set; skipping notification intent %s", intent);
            return;
        }
        try {
            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(eventsQueueUrl.get())
                    .messageBody(mapper.writeValueAsString(intent))
                    .build());
        } catch (Exception e) {
            // Review lifecycle writes are the source of truth. Notification
            // intents are async and deterministic, so operators can safely
            // replay the same intent without risking duplicate emails.
            LOG.errorf(e, "Failed to publish notification intent %s", intent);
        }
    }
}

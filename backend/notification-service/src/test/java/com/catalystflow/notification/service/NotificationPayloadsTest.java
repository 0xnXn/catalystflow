package com.catalystflow.notification.service;

import com.catalystflow.notification.service.NotificationPayloads.AutoDeadlineReminderDigestItem;
import com.catalystflow.notification.service.NotificationPayloads.AutoDeadlineReminderDigestRequest;
import com.catalystflow.notification.service.NotificationPayloads.ManualReminderJobRequest;
import com.catalystflow.notification.service.NotificationPayloads.NotificationIntent;
import com.catalystflow.notification.service.NotificationPayloads.ReviewPublishedRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificationPayloadsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void manualReminderJobRequestRoundTripsThroughJackson() throws Exception {
        ManualReminderJobRequest payload = new ManualReminderJobRequest(
                "employee-1", "review-1", "cycle-1", "employee-1",
                "SELF_REVIEW_SUBJECT", "manager-1", null, "2026-05-20");

        String json = mapper.writeValueAsString(payload);
        ManualReminderJobRequest parsed = mapper.readValue(json, ManualReminderJobRequest.class);

        assertEquals("employee-1", parsed.recipientUserId());
        assertEquals("review-1", parsed.reviewId());
        assertEquals("SELF_REVIEW_SUBJECT", parsed.targetType());
    }

    @Test
    void otherNotificationPayloadsRoundTripThroughJackson() throws Exception {
        AutoDeadlineReminderDigestRequest autoPayload = new AutoDeadlineReminderDigestRequest(
                "employee-1", "cycle-1", "May Cycle", "2026-05-24",
                java.util.List.of(new AutoDeadlineReminderDigestItem(
                        "MILESTONE_START", "2026-05-31", "m1", "m1", "Self Review")));
        ReviewPublishedRequest publishedPayload = new ReviewPublishedRequest(
                "employee-1", "cycle-1", "employee-1", "admin-1");
        NotificationIntent intent = new NotificationIntent(
                "REVIEW_PUBLISHED", null, "cycle-1", "employee-1", "admin-1");

        AutoDeadlineReminderDigestRequest parsedAuto = mapper.readValue(
                mapper.writeValueAsString(autoPayload), AutoDeadlineReminderDigestRequest.class);
        assertEquals("MILESTONE_START", parsedAuto.reminders().get(0).deadlineEvent());
        assertEquals("Self Review", parsedAuto.reminders().get(0).milestoneName());
        assertEquals("m1", parsedAuto.reminders().get(0).scopeId());
        assertEquals("admin-1", mapper.readValue(
                mapper.writeValueAsString(publishedPayload), ReviewPublishedRequest.class).publishedBy());
        assertEquals("REVIEW_PUBLISHED", mapper.readValue(
                mapper.writeValueAsString(intent), NotificationIntent.class).type());
    }
}

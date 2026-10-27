package com.catalystflow.notification.worker;

import com.catalystflow.notification.repository.NotificationJobRepository;
import com.catalystflow.notification.service.NotificationDomainLookup;
import com.catalystflow.notification.service.NotificationPayloads.ReviewRecord;
import com.catalystflow.notification.service.NotificationPayloads.UserRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationDeliveryServiceTest {

    NotificationDeliveryService service;
    NotificationJobRepository jobs;
    NotificationDomainLookup domain;
    ObjectMapper mapper;
    SesV2Client ses;

    @BeforeEach
    void setUp() {
        service = new NotificationDeliveryService();
        jobs = mock(NotificationJobRepository.class);
        domain = mock(NotificationDomainLookup.class);
        mapper = new ObjectMapper();
        ses = mock(SesV2Client.class);

        service.jobs = jobs;
        service.domain = domain;
        service.mapper = mapper;
        service.sesClient = ses;
        service.fromEmail = "no-reply@example.com";
        service.appBaseUrl = "http://localhost:4200";
        service.maxAttempts = 3;
    }

    @Test
    void missingJobIsDroppedWithoutClaim() {
        when(jobs.getJobData("job-1")).thenReturn(null);

        service.processDeliveryJob("job-1");

        verify(jobs, never()).tryClaim(anyString(), any());
    }

    @Test
    void terminalJobIsSkippedWithoutClaim() {
        when(jobs.getJobData("job-1")).thenReturn(job("job-1", "COMPLETED", "MANUAL_REMINDER", "{}"));

        service.processDeliveryJob("job-1");

        verify(jobs, never()).tryClaim(anyString(), any());
    }

    @Test
    void unclaimedPendingJobDoesNothing() {
        when(jobs.getJobData("job-1")).thenReturn(job("job-1", "PENDING", "MANUAL_REMINDER", "{}"));
        when(jobs.tryClaim(eq("job-1"), any(Instant.class))).thenReturn(false);

        service.processDeliveryJob("job-1");

        verify(jobs, never()).markCompleted(anyString(), anyString(), any());
        verify(jobs, never()).markFailed(anyString(), anyString(), any());
    }

    @Test
    void autoDigestReminderSendsOneEmailWithAllReminderItems() throws Exception {
        String requestJson = mapper.writeValueAsString(new com.catalystflow.notification.service.NotificationPayloads.AutoDeadlineReminderDigestRequest(
                "employee-1", "cycle-1", "May Cycle", "2026-05-18",
                List.of(
                        new com.catalystflow.notification.service.NotificationPayloads.AutoDeadlineReminderDigestItem(
                                "CYCLE_START", "2026-05-19", "CYCLE", null, null),
                        new com.catalystflow.notification.service.NotificationPayloads.AutoDeadlineReminderDigestItem(
                                "MILESTONE_START", "2026-05-19", "m1", "m1", "Self Review"),
                        new com.catalystflow.notification.service.NotificationPayloads.AutoDeadlineReminderDigestItem(
                                "MILESTONE_END", "2026-05-19", "m2", "m2", "Manager Review"))));
        when(jobs.getJobData("job-1")).thenReturn(job("job-1", "PENDING", "AUTO_DEADLINE_REMINDER_DIGEST", requestJson));
        when(jobs.tryClaim(eq("job-1"), any(Instant.class))).thenReturn(true);
        when(domain.user("employee-1")).thenReturn(Optional.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Active", "manager-1")));
        when(ses.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder()
                .messageId("ses-1")
                .build());

        service.processDeliveryJob("job-1");

        verify(jobs).markCompleted(eq("job-1"), contains("ses-1"), any(Instant.class));
        ArgumentCaptor<SendEmailRequest> sent = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(sent.capture());
        String raw = rawEmail(sent.getValue());
        String body = textPart(raw);
        String html = htmlPart(raw);
        assertNull(sent.getValue().content().simple());
        assertNotNull(sent.getValue().content().raw());
        assertTrue(raw.contains("Content-ID: <catalystflow-solutions-logo>"));
        assertTrue(raw.contains("Subject: " + encodedHeader("CatalystFlow reminders for May Cycle")));
        assertTrue(body.contains("Here are your upcoming CatalystFlow dates for May Cycle:"));
        assertTrue(body.contains("- Review cycle starts tomorrow, on 2026-05-19"));
        assertTrue(body.contains("- Self Review starts tomorrow, on 2026-05-19"));
        assertTrue(body.contains("- Manager Review is due tomorrow, on 2026-05-19"));
        assertTrue(html.contains("cid:catalystflow-solutions-logo"));
        assertTrue(html.contains("Open CatalystFlow"));
        assertFalse(body.contains("Review ID:"));
        assertFalse(body.contains("null"));
    }

    @Test
    void autoDigestReminderSkipsInactiveRecipient() throws Exception {
        String requestJson = mapper.writeValueAsString(new com.catalystflow.notification.service.NotificationPayloads.AutoDeadlineReminderDigestRequest(
                "employee-1", "cycle-1", "May Cycle", "2026-05-18",
                List.of(new com.catalystflow.notification.service.NotificationPayloads.AutoDeadlineReminderDigestItem(
                        "CYCLE_END", "2026-05-19", "CYCLE", null, null))));
        when(jobs.getJobData("job-1")).thenReturn(job("job-1", "PENDING", "AUTO_DEADLINE_REMINDER_DIGEST", requestJson));
        when(jobs.tryClaim(eq("job-1"), any(Instant.class))).thenReturn(true);
        when(domain.user("employee-1")).thenReturn(Optional.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Inactive", "manager-1")));

        service.processDeliveryJob("job-1");

        verify(jobs).markSkipped(eq("job-1"), contains("inactive"), any(Instant.class));
        verifyNoInteractions(ses);
    }

    @Test
    void autoDigestReminderSendsSingleItemDigest() throws Exception {
        String requestJson = mapper.writeValueAsString(new com.catalystflow.notification.service.NotificationPayloads.AutoDeadlineReminderDigestRequest(
                "employee-1", "cycle-1", "May Cycle", "2026-05-18",
                List.of(new com.catalystflow.notification.service.NotificationPayloads.AutoDeadlineReminderDigestItem(
                        "MILESTONE_START", "2026-05-19", "m1", "m1", "Self Review"))));
        when(jobs.getJobData("job-1")).thenReturn(job("job-1", "PENDING", "AUTO_DEADLINE_REMINDER_DIGEST", requestJson));
        when(jobs.tryClaim(eq("job-1"), any(Instant.class))).thenReturn(true);
        when(domain.user("employee-1")).thenReturn(Optional.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Active", "manager-1")));
        when(ses.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder()
                .messageId("ses-1")
                .build());

        service.processDeliveryJob("job-1");

        ArgumentCaptor<SendEmailRequest> sent = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(sent.capture());
        String body = textPart(rawEmail(sent.getValue()));
        assertTrue(body.contains("Here are your upcoming CatalystFlow dates for May Cycle:"));
        assertTrue(body.contains("- Self Review starts tomorrow, on 2026-05-19"));
    }

    @Test
    void manualSelfReminderWithoutReviewIdSendsWhenNoSelfRowExists() throws Exception {
        String requestJson = mapper.writeValueAsString(new com.catalystflow.notification.service.NotificationPayloads.ManualReminderJobRequest(
                "employee-1", null, "cycle-1", "employee-1",
                "SELF_REVIEW_SUBJECT", "manager-1", null, "2026-05-19"));
        when(jobs.getJobData("job-1")).thenReturn(job("job-1", "PENDING", "MANUAL_REMINDER", requestJson));
        when(jobs.tryClaim(eq("job-1"), any(Instant.class))).thenReturn(true);
        when(domain.reviewsForCycle("cycle-1")).thenReturn(List.of());
        when(domain.user("employee-1")).thenReturn(Optional.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Active", "manager-1")));
        when(ses.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder()
                .messageId("ses-1")
                .build());

        service.processDeliveryJob("job-1");

        verify(jobs).markCompleted(eq("job-1"), contains("ses-1"), any(Instant.class));
        ArgumentCaptor<SendEmailRequest> sent = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(sent.capture());
        String body = textPart(rawEmail(sent.getValue()));
        assertTrue(body.contains("You have a review task waiting in CatalystFlow. Please open CatalystFlow and complete it when you can."));
        assertFalse(body.contains("Review ID:"));
        assertFalse(body.contains("null"));
    }

    @Test
    void manualSelfReminderWithoutReviewIdSkipsWhenSelfReviewIsCompleted() throws Exception {
        String requestJson = mapper.writeValueAsString(new com.catalystflow.notification.service.NotificationPayloads.ManualReminderJobRequest(
                "employee-1", null, "cycle-1", "employee-1",
                "SELF_REVIEW_SUBJECT", "manager-1", null, "2026-05-19"));
        when(jobs.getJobData("job-1")).thenReturn(job("job-1", "PENDING", "MANUAL_REMINDER", requestJson));
        when(jobs.tryClaim(eq("job-1"), any(Instant.class))).thenReturn(true);
        when(domain.reviewsForCycle("cycle-1")).thenReturn(List.of(
                new ReviewRecord("review-self", "cycle-1", "template-1",
                        "employee-1", "employee-1", "SELF", "COMPLETED")));

        service.processDeliveryJob("job-1");

        verify(jobs).markSkipped(eq("job-1"), contains("no longer incomplete"), any(Instant.class));
        verifyNoInteractions(ses);
    }

    @Test
    void manualManagerReminderWithoutReviewIdSendsWhenNoManagerRowExists() throws Exception {
        String requestJson = mapper.writeValueAsString(new com.catalystflow.notification.service.NotificationPayloads.ManualReminderJobRequest(
                "manager-1", null, "cycle-1", "employee-1",
                "MANAGER_REVIEW_MANAGER", "admin-1", null, "2026-05-19"));
        when(jobs.getJobData("job-1")).thenReturn(job("job-1", "PENDING", "MANUAL_REMINDER", requestJson));
        when(jobs.tryClaim(eq("job-1"), any(Instant.class))).thenReturn(true);
        when(domain.reviewsForCycle("cycle-1")).thenReturn(List.of());
        when(domain.user("manager-1")).thenReturn(Optional.of(
                new UserRecord("manager-1", "manager@example.com", "Mgr", "One", "MANAGER", "Active", null)));
        when(ses.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder()
                .messageId("ses-1")
                .build());

        service.processDeliveryJob("job-1");

        verify(jobs).markCompleted(eq("job-1"), contains("ses-1"), any(Instant.class));
        ArgumentCaptor<SendEmailRequest> sent = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(sent.capture());
        String body = textPart(rawEmail(sent.getValue()));
        assertTrue(body.contains("You have a review task waiting in CatalystFlow. Please open CatalystFlow and complete it when you can."));
        assertFalse(body.contains("Review ID:"));
        assertFalse(body.contains("null"));
    }

    @Test
    void manualManagerReminderWithoutReviewIdSendsWhenMatchingManagerRowIsIncomplete() throws Exception {
        String requestJson = mapper.writeValueAsString(new com.catalystflow.notification.service.NotificationPayloads.ManualReminderJobRequest(
                "manager-1", null, "cycle-1", "employee-1",
                "MANAGER_REVIEW_MANAGER", "admin-1", null, "2026-05-19"));
        when(jobs.getJobData("job-1")).thenReturn(job("job-1", "PENDING", "MANUAL_REMINDER", requestJson));
        when(jobs.tryClaim(eq("job-1"), any(Instant.class))).thenReturn(true);
        when(domain.reviewsForCycle("cycle-1")).thenReturn(List.of(
                new ReviewRecord("review-manager", "cycle-1", "template-1",
                        "employee-1", "manager-1", "MANAGER", "IN_PROGRESS")));
        when(domain.user("manager-1")).thenReturn(Optional.of(
                new UserRecord("manager-1", "manager@example.com", "Mgr", "One", "MANAGER", "Active", null)));
        when(ses.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder()
                .messageId("ses-1")
                .build());

        service.processDeliveryJob("job-1");

        verify(jobs).markCompleted(eq("job-1"), contains("ses-1"), any(Instant.class));
    }

    @Test
    void manualManagerReminderWithoutReviewIdSkipsWhenMatchingManagerRowIsCompleted() throws Exception {
        String requestJson = mapper.writeValueAsString(new com.catalystflow.notification.service.NotificationPayloads.ManualReminderJobRequest(
                "manager-1", null, "cycle-1", "employee-1",
                "MANAGER_REVIEW_MANAGER", "admin-1", null, "2026-05-19"));
        when(jobs.getJobData("job-1")).thenReturn(job("job-1", "PENDING", "MANUAL_REMINDER", requestJson));
        when(jobs.tryClaim(eq("job-1"), any(Instant.class))).thenReturn(true);
        when(domain.reviewsForCycle("cycle-1")).thenReturn(List.of(
                new ReviewRecord("review-manager", "cycle-1", "template-1",
                        "employee-1", "manager-1", "MANAGER", "COMPLETED")));

        service.processDeliveryJob("job-1");

        verify(jobs).markSkipped(eq("job-1"), contains("Manager review is no longer incomplete"), any(Instant.class));
        verifyNoInteractions(ses);
    }

    @Test
    void manualManagerReminderWithoutReviewIdIgnoresRowsForOtherManagers() throws Exception {
        String requestJson = mapper.writeValueAsString(new com.catalystflow.notification.service.NotificationPayloads.ManualReminderJobRequest(
                "manager-1", null, "cycle-1", "employee-1",
                "MANAGER_REVIEW_MANAGER", "admin-1", null, "2026-05-19"));
        when(jobs.getJobData("job-1")).thenReturn(job("job-1", "PENDING", "MANUAL_REMINDER", requestJson));
        when(jobs.tryClaim(eq("job-1"), any(Instant.class))).thenReturn(true);
        when(domain.reviewsForCycle("cycle-1")).thenReturn(List.of(
                new ReviewRecord("review-manager", "cycle-1", "template-1",
                        "employee-1", "other-manager", "MANAGER", "COMPLETED")));
        when(domain.user("manager-1")).thenReturn(Optional.of(
                new UserRecord("manager-1", "manager@example.com", "Mgr", "One", "MANAGER", "Active", null)));
        when(ses.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder()
                .messageId("ses-1")
                .build());

        service.processDeliveryJob("job-1");

        verify(jobs).markCompleted(eq("job-1"), contains("ses-1"), any(Instant.class));
    }

    @Test
    void manualReminderForCompletedReviewMarksSkipped() throws Exception {
        String requestJson = mapper.writeValueAsString(new com.catalystflow.notification.service.NotificationPayloads.ManualReminderJobRequest(
                "employee-1", "review-1", "cycle-1", "employee-1",
                "SELF_REVIEW_SUBJECT", "manager-1", null, "2026-05-19"));
        when(jobs.getJobData("job-1")).thenReturn(job("job-1", "PENDING", "MANUAL_REMINDER", requestJson));
        when(jobs.tryClaim(eq("job-1"), any(Instant.class))).thenReturn(true);
        when(domain.review("review-1")).thenReturn(Optional.of(
                new ReviewRecord("review-1", "cycle-1", "template-1",
                        "employee-1", "employee-1", "SELF", "COMPLETED")));

        service.processDeliveryJob("job-1");

        verify(jobs).markSkipped(eq("job-1"), contains("no longer incomplete"), any(Instant.class));
    }

    @Test
    void publishedNotificationWithoutPublishedBundleMarksSkipped() throws Exception {
        String requestJson = mapper.writeValueAsString(new com.catalystflow.notification.service.NotificationPayloads.ReviewPublishedRequest(
                "employee-1", "cycle-1", "employee-1", "admin-1"));
        when(jobs.getJobData("job-1")).thenReturn(job("job-1", "PENDING", "REVIEW_PUBLISHED", requestJson));
        when(jobs.tryClaim(eq("job-1"), any(Instant.class))).thenReturn(true);
        when(domain.reviewsForCycle("cycle-1")).thenReturn(List.of(
                new ReviewRecord("review-1", "cycle-1", "template-1",
                        "employee-1", "employee-1", "SELF", "COMPLETED")));

        service.processDeliveryJob("job-1");

        verify(jobs).markSkipped(eq("job-1"), contains("not published"), any(Instant.class));
    }

    @Test
    void missingRecipientEmailMarksFailedWithoutRetry() throws Exception {
        String requestJson = mapper.writeValueAsString(new com.catalystflow.notification.service.NotificationPayloads.ManualReminderJobRequest(
                "employee-1", "review-1", "cycle-1", "employee-1",
                "SELF_REVIEW_SUBJECT", "manager-1", null, "2026-05-19"));
        when(jobs.getJobData("job-1")).thenReturn(job("job-1", "PENDING", "MANUAL_REMINDER", requestJson));
        when(jobs.tryClaim(eq("job-1"), any(Instant.class))).thenReturn(true);
        when(domain.review("review-1")).thenReturn(Optional.of(
                new ReviewRecord("review-1", "cycle-1", "template-1",
                        "employee-1", "employee-1", "SELF", "IN_PROGRESS")));
        when(domain.user("employee-1")).thenReturn(Optional.of(
                new UserRecord("employee-1", "", "Emp", "One", "EMPLOYEE", "Active", "manager-1")));

        service.processDeliveryJob("job-1");

        verify(jobs).markFailed(eq("job-1"), contains("email is missing"), any(Instant.class));
        verify(jobs, never()).markRetryableFailure(anyString(), anyInt(), anyString(), any());
    }

    private String rawEmail(SendEmailRequest request) {
        return new String(request.content().raw().data().asByteArray(), StandardCharsets.UTF_8);
    }

    private String textPart(String rawEmail) {
        return part(rawEmail, "text/plain; charset=UTF-8");
    }

    private String htmlPart(String rawEmail) {
        return part(rawEmail, "text/html; charset=UTF-8");
    }

    private String part(String rawEmail, String contentType) {
        var matcher = Pattern.compile("Content-Type: " + Pattern.quote(contentType)
                + "\\r\\nContent-Transfer-Encoding: base64\\r\\n\\r\\n([A-Za-z0-9+/=\\r\\n]+)\\r\\n--")
                .matcher(rawEmail);
        assertTrue(matcher.find(), "Missing MIME part " + contentType + " in:\n" + rawEmail);
        return new String(Base64.getMimeDecoder().decode(matcher.group(1)), StandardCharsets.UTF_8);
    }

    private String encodedHeader(String value) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private NotificationJobRepository.JobData job(String id, String status, String taskType, String requestJson) {
        return new NotificationJobRepository.JobData(id, status, taskType, requestJson, 0);
    }
}

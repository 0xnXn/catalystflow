package com.catalystflow.notification.service;

import com.catalystflow.notification.model.ManualReminderRequest;
import com.catalystflow.notification.model.ManualReminderTargetType;
import com.catalystflow.notification.model.NotificationJobStatusResponse;
import com.catalystflow.notification.model.NotificationJobStatus;
import com.catalystflow.notification.repository.NotificationJobRepository;
import com.catalystflow.notification.service.NotificationPayloads.AutoDeadlineReminderDigestRequest;
import com.catalystflow.notification.service.NotificationPayloads.CycleRecord;
import com.catalystflow.notification.service.NotificationPayloads.ManualReminderJobRequest;
import com.catalystflow.notification.service.NotificationPayloads.MilestoneRecord;
import com.catalystflow.notification.service.NotificationPayloads.ReviewRecord;
import com.catalystflow.notification.service.NotificationPayloads.UserRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationServiceTest {

    NotificationService service;
    NotificationJobRepository jobs;
    NotificationDomainLookup domain;
    SqsClient sqs;

    @BeforeEach
    void setUp() {
        service = new NotificationService();
        jobs = mock(NotificationJobRepository.class);
        domain = mock(NotificationDomainLookup.class);
        sqs = mock(SqsClient.class);

        service.jobs = jobs;
        service.domain = domain;
        service.mapper = new ObjectMapper();
        service.sqs = sqs;
        service.jobsQueueUrl = Optional.empty();
        service.startReminderOffsets = "7,0";
        service.endReminderOffsets = "14,7,3,1,0";
        service.jobTtlDays = 90;
    }

    @Test
    void manualSelfReminder_createsJobWithoutSelfReviewRow() throws Exception {
        ManualReminderRequest request = new ManualReminderRequest()
                .cycleId("cycle-1")
                .targetType(ManualReminderTargetType.SELF_REVIEW_SUBJECT)
                .subjectUserIds(List.of("employee-1"));

        when(domain.user("employee-1")).thenReturn(Optional.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Active", "manager-1")));
        when(domain.reviewsForCycle("cycle-1")).thenReturn(List.of());
        when(jobs.createPending(anyString(), eq("MANUAL_REMINDER"), anyString(), anyLong(), any()))
                .thenReturn(true);
        when(jobs.getStatus(anyString())).thenReturn(status("PENDING"));

        var response = service.sendManualReminder(request, "manager-1", false, true);

        assertEquals(1, response.getCreated().size());
        assertNull(response.getCreated().get(0).getReviewId());
        assertEquals("employee-1", response.getCreated().get(0).getRecipientUserId());
        assertTrue(response.getCreated().get(0).getJobId()
                .contains("#SELF_REVIEW_SUBJECT#cycle-1#employee-1#employee-1"));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(jobs).createPending(anyString(), eq("MANUAL_REMINDER"), json.capture(), anyLong(), any());
        ManualReminderJobRequest payload = service.mapper.readValue(json.getValue(), ManualReminderJobRequest.class);
        assertNull(payload.reviewId());
        assertEquals("employee-1", payload.recipientUserId());
    }

    @Test
    void manualSelfReminder_withIncompleteRowIncludesReviewId() {
        ManualReminderRequest request = new ManualReminderRequest()
                .cycleId("cycle-1")
                .targetType(ManualReminderTargetType.SELF_REVIEW_SUBJECT)
                .subjectUserIds(List.of("employee-1"));

        when(domain.user("employee-1")).thenReturn(Optional.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Active", "manager-1")));
        when(domain.reviewsForCycle("cycle-1")).thenReturn(List.of(
                new ReviewRecord("review-self", "cycle-1", "template-1",
                        "employee-1", "employee-1", "SELF", "IN_PROGRESS")));
        when(jobs.createPending(anyString(), eq("MANUAL_REMINDER"), anyString(), anyLong(), any()))
                .thenReturn(true);
        when(jobs.getStatus(anyString())).thenReturn(status("PENDING"));

        var response = service.sendManualReminder(request, "manager-1", false, true);

        assertEquals(1, response.getCreated().size());
        assertEquals("review-self", response.getCreated().get(0).getReviewId());
    }

    @Test
    void manualSelfReminder_withCompletedRowSkipsNotRemindable() {
        ManualReminderRequest request = new ManualReminderRequest()
                .cycleId("cycle-1")
                .targetType(ManualReminderTargetType.SELF_REVIEW_SUBJECT)
                .subjectUserIds(List.of("employee-1"));

        when(domain.user("employee-1")).thenReturn(Optional.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Active", "manager-1")));
        when(domain.reviewsForCycle("cycle-1")).thenReturn(List.of(
                new ReviewRecord("review-self", "cycle-1", "template-1",
                        "employee-1", "employee-1", "SELF", "COMPLETED")));

        var response = service.sendManualReminder(request, "manager-1", false, true);

        assertTrue(response.getCreated().isEmpty());
        assertEquals(1, response.getSkipped().size());
        assertEquals("review-self", response.getSkipped().get(0).getReviewId());
        assertEquals("NOT_REMINDABLE", response.getSkipped().get(0).getReason().toString());
        verify(jobs, never()).createPending(anyString(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void manualSelfReminder_dedupesByCycleSubjectRecipientDate() {
        ManualReminderRequest request = new ManualReminderRequest()
                .cycleId("cycle-1")
                .targetType(ManualReminderTargetType.SELF_REVIEW_SUBJECT)
                .subjectUserIds(List.of("employee-1"));

        when(domain.user("employee-1")).thenReturn(Optional.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Active", "manager-1")));
        when(domain.reviewsForCycle("cycle-1")).thenReturn(
                List.of(),
                List.of(new ReviewRecord("review-self", "cycle-1", "template-1",
                        "employee-1", "employee-1", "SELF", "IN_PROGRESS")));
        when(jobs.createPending(anyString(), eq("MANUAL_REMINDER"), anyString(), anyLong(), any()))
                .thenReturn(true, false);
        when(jobs.getStatus(anyString())).thenReturn(status("PENDING"), status("COMPLETED"));

        var first = service.sendManualReminder(request, "manager-1", false, true);
        var second = service.sendManualReminder(request, "manager-1", false, true);

        assertEquals(1, first.getCreated().size());
        assertEquals(1, second.getSkipped().size());
        assertEquals("ALREADY_REMINDED_TODAY", second.getSkipped().get(0).getReason().toString());

        ArgumentCaptor<String> jobIds = ArgumentCaptor.forClass(String.class);
        verify(jobs, times(2)).createPending(jobIds.capture(), eq("MANUAL_REMINDER"), anyString(), anyLong(), any());
        assertEquals(jobIds.getAllValues().get(0), jobIds.getAllValues().get(1));
        assertTrue(jobIds.getAllValues().get(0).contains("#SELF_REVIEW_SUBJECT#cycle-1#employee-1#employee-1"));
    }

    @Test
    void manualPeerReminder_dedupesPerPeerReviewer() {
        ManualReminderRequest request = new ManualReminderRequest()
                .cycleId("cycle-1")
                .targetType(ManualReminderTargetType.PEER_REVIEW_EMPLOYEE)
                .subjectUserIds(List.of("employee-1"));

        when(domain.user("employee-1")).thenReturn(Optional.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Active", "manager-1")));
        when(domain.reviewsForCycle("cycle-1")).thenReturn(List.of(
                new ReviewRecord("review-peer-1", "cycle-1", "template-1",
                        "employee-1", "peer-1", "PEER_360", "NOT_STARTED"),
                new ReviewRecord("review-peer-2", "cycle-1", "template-1",
                        "employee-1", "peer-2", "PEER_360", "IN_PROGRESS")));
        when(jobs.createPending(anyString(), eq("MANUAL_REMINDER"), anyString(), anyLong(), any()))
                .thenReturn(true);
        when(jobs.getStatus(anyString())).thenReturn(status("PENDING"));

        var response = service.sendManualReminder(request, "manager-1", false, true);

        assertEquals(2, response.getCreated().size());
        ArgumentCaptor<String> jobIds = ArgumentCaptor.forClass(String.class);
        verify(jobs, times(2)).createPending(jobIds.capture(), eq("MANUAL_REMINDER"), anyString(), anyLong(), any());
        assertTrue(jobIds.getAllValues().get(0).contains("#PEER_REVIEW_EMPLOYEE#cycle-1#employee-1#peer-1"));
        assertTrue(jobIds.getAllValues().get(1).contains("#PEER_REVIEW_EMPLOYEE#cycle-1#employee-1#peer-2"));
    }

    @Test
    void manualPeerReminder_allowsPeerOutsideManagerTeamWhenSubjectIsDirectReport() {
        ManualReminderRequest request = new ManualReminderRequest()
                .cycleId("cycle-1")
                .targetType(ManualReminderTargetType.PEER_REVIEW_EMPLOYEE)
                .subjectUserIds(List.of("employee-1"));

        when(domain.user("employee-1")).thenReturn(Optional.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Active", "manager-1")));
        when(domain.reviewsForCycle("cycle-1")).thenReturn(List.of(
                new ReviewRecord("review-peer", "cycle-1", "template-1",
                        "employee-1", "peer-outside-team", "PEER_360", "NOT_STARTED")));
        when(jobs.createPending(anyString(), eq("MANUAL_REMINDER"), anyString(), anyLong(), any()))
                .thenReturn(true);
        when(jobs.getStatus(anyString())).thenReturn(status("PENDING"));

        var response = service.sendManualReminder(request, "manager-1", false, true);

        assertEquals(1, response.getCreated().size());
        assertEquals("MANUAL_REMINDER", response.getCreated().get(0).getTaskType().toString());
        assertEquals("peer-outside-team", response.getCreated().get(0).getRecipientUserId());
        assertTrue(response.getSkipped().isEmpty());
    }

    @Test
    void manualManagerReminder_createsJobWithoutManagerReviewRow() throws Exception {
        ManualReminderRequest request = new ManualReminderRequest()
                .cycleId("cycle-1")
                .targetType(ManualReminderTargetType.MANAGER_REVIEW_MANAGER)
                .subjectUserIds(List.of("employee-1"));

        when(domain.reviewsForCycle("cycle-1")).thenReturn(List.of());
        when(domain.user("employee-1")).thenReturn(Optional.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Active", "manager-1")));
        when(domain.user("manager-1")).thenReturn(Optional.of(
                new UserRecord("manager-1", "manager@example.com", "Mgr", "One", "MANAGER", "Active", null)));
        when(jobs.createPending(anyString(), eq("MANUAL_REMINDER"), anyString(), anyLong(), any()))
                .thenReturn(true);
        when(jobs.getStatus(anyString())).thenReturn(status("PENDING"));

        var response = service.sendManualReminder(request, "admin-1", true, false);

        assertEquals(1, response.getCreated().size());
        assertNull(response.getCreated().get(0).getReviewId());
        assertEquals("manager-1", response.getCreated().get(0).getRecipientUserId());
        assertTrue(response.getCreated().get(0).getJobId()
                .contains("#MANAGER_REVIEW_MANAGER#cycle-1#employee-1#manager-1"));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(jobs).createPending(anyString(), eq("MANUAL_REMINDER"), json.capture(), anyLong(), any());
        ManualReminderJobRequest payload = service.mapper.readValue(json.getValue(), ManualReminderJobRequest.class);
        assertNull(payload.reviewId());
        assertEquals("manager-1", payload.recipientUserId());
        assertEquals("employee-1", payload.subjectUserId());
        assertEquals("MANAGER_REVIEW_MANAGER", payload.targetType());
    }

    @Test
    void manualManagerReminder_withIncompleteRowIncludesReviewId() {
        ManualReminderRequest request = new ManualReminderRequest()
                .cycleId("cycle-1")
                .targetType(ManualReminderTargetType.MANAGER_REVIEW_MANAGER)
                .subjectUserIds(List.of("employee-1"));

        when(domain.reviewsForCycle("cycle-1")).thenReturn(List.of(
                new ReviewRecord("review-manager", "cycle-1", "template-1",
                        "employee-1", "manager-1", "MANAGER", "IN_PROGRESS")));
        when(jobs.createPending(anyString(), eq("MANUAL_REMINDER"), anyString(), anyLong(), any()))
                .thenReturn(true);
        when(jobs.getStatus(anyString())).thenReturn(status("PENDING"));

        var response = service.sendManualReminder(request, "admin-1", true, false);

        assertEquals(1, response.getCreated().size());
        assertEquals("review-manager", response.getCreated().get(0).getReviewId());
        assertEquals("manager-1", response.getCreated().get(0).getRecipientUserId());
    }

    @Test
    void manualManagerReminder_withCompletedRowSkipsNotRemindable() {
        ManualReminderRequest request = new ManualReminderRequest()
                .cycleId("cycle-1")
                .targetType(ManualReminderTargetType.MANAGER_REVIEW_MANAGER)
                .subjectUserIds(List.of("employee-1"));

        when(domain.reviewsForCycle("cycle-1")).thenReturn(List.of(
                new ReviewRecord("review-manager", "cycle-1", "template-1",
                        "employee-1", "manager-1", "MANAGER", "PUBLISHED")));

        var response = service.sendManualReminder(request, "admin-1", true, false);

        assertTrue(response.getCreated().isEmpty());
        assertEquals(1, response.getSkipped().size());
        assertEquals("review-manager", response.getSkipped().get(0).getReviewId());
        assertEquals("NOT_REMINDABLE", response.getSkipped().get(0).getReason().toString());
        verify(jobs, never()).createPending(anyString(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void manualManagerReminder_skipsWhenSubjectHasNoAssignedManager() {
        ManualReminderRequest request = new ManualReminderRequest()
                .cycleId("cycle-1")
                .targetType(ManualReminderTargetType.MANAGER_REVIEW_MANAGER)
                .subjectUserIds(List.of("employee-1"));

        when(domain.reviewsForCycle("cycle-1")).thenReturn(List.of());
        when(domain.user("employee-1")).thenReturn(Optional.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Active", null)));

        var response = service.sendManualReminder(request, "admin-1", true, false);

        assertTrue(response.getCreated().isEmpty());
        assertEquals(1, response.getSkipped().size());
        assertEquals("NO_REVIEW_FOUND", response.getSkipped().get(0).getReason().toString());
        verify(jobs, never()).createPending(anyString(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void manualManagerReminder_skipsWhenAssignedManagerIsInactiveOrMissing() {
        ManualReminderRequest request = new ManualReminderRequest()
                .cycleId("cycle-1")
                .targetType(ManualReminderTargetType.MANAGER_REVIEW_MANAGER)
                .subjectUserIds(List.of("employee-1"));

        when(domain.reviewsForCycle("cycle-1")).thenReturn(List.of(), List.of());
        when(domain.user("employee-1")).thenReturn(Optional.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Active", "manager-1")));
        when(domain.user("manager-1")).thenReturn(Optional.empty(), Optional.of(
                new UserRecord("manager-1", "manager@example.com", "Mgr", "One", "MANAGER", "Inactive", null)));

        var missingManager = service.sendManualReminder(request, "admin-1", true, false);
        var inactiveManager = service.sendManualReminder(request, "admin-1", true, false);

        assertTrue(missingManager.getCreated().isEmpty());
        assertEquals("NO_REVIEW_FOUND", missingManager.getSkipped().get(0).getReason().toString());
        assertTrue(inactiveManager.getCreated().isEmpty());
        assertEquals("NO_REVIEW_FOUND", inactiveManager.getSkipped().get(0).getReason().toString());
        verify(jobs, never()).createPending(anyString(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    void manualManagerReminder_dedupesAcrossMissingRowAndLaterStartedRow() {
        ManualReminderRequest request = new ManualReminderRequest()
                .cycleId("cycle-1")
                .targetType(ManualReminderTargetType.MANAGER_REVIEW_MANAGER)
                .subjectUserIds(List.of("employee-1"));

        when(domain.reviewsForCycle("cycle-1")).thenReturn(
                List.of(),
                List.of(new ReviewRecord("review-manager", "cycle-1", "template-1",
                        "employee-1", "manager-1", "MANAGER", "IN_PROGRESS")));
        when(domain.user("employee-1")).thenReturn(Optional.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Active", "manager-1")));
        when(domain.user("manager-1")).thenReturn(Optional.of(
                new UserRecord("manager-1", "manager@example.com", "Mgr", "One", "MANAGER", "Active", null)));
        when(jobs.createPending(anyString(), eq("MANUAL_REMINDER"), anyString(), anyLong(), any()))
                .thenReturn(true, false);
        when(jobs.getStatus(anyString())).thenReturn(status("PENDING"), status("COMPLETED"));

        var first = service.sendManualReminder(request, "admin-1", true, false);
        var second = service.sendManualReminder(request, "admin-1", true, false);

        assertEquals(1, first.getCreated().size());
        assertNull(first.getCreated().get(0).getReviewId());
        assertEquals(1, second.getSkipped().size());
        assertEquals("review-manager", second.getSkipped().get(0).getReviewId());
        assertEquals("ALREADY_REMINDED_TODAY", second.getSkipped().get(0).getReason().toString());

        ArgumentCaptor<String> jobIds = ArgumentCaptor.forClass(String.class);
        verify(jobs, times(2)).createPending(jobIds.capture(), eq("MANUAL_REMINDER"), anyString(), anyLong(), any());
        assertEquals(jobIds.getAllValues().get(0), jobIds.getAllValues().get(1));
        assertTrue(jobIds.getAllValues().get(0)
                .contains("#MANAGER_REVIEW_MANAGER#cycle-1#employee-1#manager-1"));
    }

    @Test
    void manualReminder_skipsDuplicateSameDayJob() {
        ManualReminderRequest request = new ManualReminderRequest()
                .cycleId("cycle-1")
                .targetType(ManualReminderTargetType.SELF_REVIEW_SUBJECT)
                .subjectUserIds(List.of("employee-1"));

        when(domain.user("employee-1")).thenReturn(Optional.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Active", "manager-1")));
        when(domain.reviewsForCycle("cycle-1")).thenReturn(List.of(
                new ReviewRecord("review-self", "cycle-1", "template-1",
                        "employee-1", "employee-1", "SELF", "IN_PROGRESS")));
        when(jobs.createPending(anyString(), eq("MANUAL_REMINDER"), anyString(), anyLong(), any()))
                .thenReturn(false);
        when(jobs.getStatus(anyString())).thenReturn(status("COMPLETED"));

        var response = service.sendManualReminder(request, "manager-1", false, true);

        assertTrue(response.getCreated().isEmpty());
        assertEquals(1, response.getSkipped().size());
        assertEquals("ALREADY_REMINDED_TODAY", response.getSkipped().get(0).getReason().toString());
        assertEquals("COMPLETED", response.getSkipped().get(0).getStatus().toString());
    }

    @Test
    void statusForValidDeterministicJobBeforeMaterializationReturnsPending() {
        when(jobs.getStatus("PUBLISHED#cycle-1#employee-1")).thenReturn(null);

        NotificationJobStatusResponse response = service.getStatus("PUBLISHED#cycle-1#employee-1");

        assertNotNull(response);
        assertEquals("PENDING", response.getStatus().toString());
        assertEquals("REVIEW_PUBLISHED", response.getTaskType().toString());
        assertFalse(response.getMaterialized());
    }

    @Test
    void statusForAutoDigestJobBeforeMaterializationReturnsPending() {
        when(jobs.getStatus("AUTO_DIGEST#2026-05-19#cycle-1#employee-1")).thenReturn(null);

        NotificationJobStatusResponse response = service.getStatus("AUTO_DIGEST#2026-05-19#cycle-1#employee-1");

        assertNotNull(response);
        assertEquals("PENDING", response.getStatus().toString());
        assertEquals("AUTO_DEADLINE_REMINDER_DIGEST", response.getTaskType().toString());
        assertFalse(response.getMaterialized());
    }

    @Test
    void manualReminder_rejectsNonManagerNonAdmin() {
        ManualReminderRequest request = new ManualReminderRequest()
                .cycleId("cycle-1")
                .targetType(ManualReminderTargetType.SELF_REVIEW_SUBJECT)
                .subjectUserIds(List.of("employee-1"));

        assertThrows(ForbiddenException.class,
                () -> service.sendManualReminder(request, "employee-1", false, false));
    }

    @Test
    void manualReminder_rejectsMissingCycleId() {
        ManualReminderRequest request = new ManualReminderRequest()
                .targetType(ManualReminderTargetType.SELF_REVIEW_SUBJECT)
                .subjectUserIds(List.of("employee-1"));

        assertThrows(BadRequestException.class,
                () -> service.sendManualReminder(request, "manager-1", false, true));
    }

    @Test
    void manualManagerReminder_skipsWhenRecipientManagerIsNotDirectReport() {
        ManualReminderRequest request = new ManualReminderRequest()
                .cycleId("cycle-1")
                .targetType(ManualReminderTargetType.MANAGER_REVIEW_MANAGER)
                .subjectUserIds(List.of("employee-1"));
        when(domain.reviewsForCycle("cycle-1")).thenReturn(List.of(
                new ReviewRecord("review-manager", "cycle-1", "template-1",
                        "employee-1", "other-manager", "MANAGER", "IN_PROGRESS")));
        when(domain.user("other-manager")).thenReturn(Optional.of(
                new UserRecord("other-manager", "other@example.com", "Other", "Mgr", "MANAGER", "Active", "someone-else")));

        var response = service.sendManualReminder(request, "manager-1", false, true);

        assertTrue(response.getCreated().isEmpty());
        assertEquals("FORBIDDEN", response.getSkipped().get(0).getReason().toString());
    }

    @Test
    void enqueueReviewPublished_createsDeterministicJob() {
        when(jobs.createPending(eq("PUBLISHED#cycle-1#employee-1"), eq("REVIEW_PUBLISHED"),
                anyString(), anyLong(), any())).thenReturn(true);
        when(jobs.getStatus("PUBLISHED#cycle-1#employee-1")).thenReturn(status("PENDING"));

        var summary = service.enqueueReviewPublished("cycle-1", "employee-1", "admin-1");

        assertEquals("PUBLISHED#cycle-1#employee-1", summary.getJobId());
        assertEquals("REVIEW_PUBLISHED", summary.getTaskType().toString());
        assertTrue(summary.getMaterialized());
    }

    @Test
    void runDailyMaintenance_syncsStatusesBeforeCreatingEventReminders() {
        when(domain.syncCycleStatuses(java.time.LocalDate.parse("2026-05-19")))
                .thenReturn(new NotificationPayloads.MaintenanceResult(1, 2));
        when(domain.cyclesForStatus("SCHEDULED")).thenReturn(List.of());
        when(domain.cyclesForStatus("ACTIVE")).thenReturn(List.of());
        when(domain.activeUsers()).thenReturn(List.of());

        var result = service.runDailyMaintenance(java.time.LocalDate.parse("2026-05-19"));

        assertEquals(1, result.cyclesUpdated());
        assertEquals(2, result.milestonesUpdated());
        var ordered = inOrder(domain);
        ordered.verify(domain).syncCycleStatuses(java.time.LocalDate.parse("2026-05-19"));
        ordered.verify(domain).activeUsers();
        ordered.verify(domain).cyclesForStatus("SCHEDULED");
        ordered.verify(domain).cyclesForStatus("ACTIVE");
    }

    @Test
    void enqueueAutoDeadlineReminders_createsCycleDigestJobsForAssignedRoles() throws Exception {
        when(domain.cyclesForStatus("SCHEDULED")).thenReturn(List.of());
        when(domain.cyclesForStatus("ACTIVE")).thenReturn(List.of(
                new CycleRecord("cycle-1", "Cycle", "ACTIVE", "2026-05-19", "2026-05-25",
                        List.of(
                                new MilestoneRecord("m1", "Self Review", "2026-06-01", "2026-06-05", "NOT_STARTED", "SELF", List.of("EMPLOYEE")),
                                new MilestoneRecord("m2", "Manager Review", "2026-06-03", "2026-06-04", "NOT_STARTED", "MANAGER", List.of("MANAGER"))))));
        when(domain.activeUsers()).thenReturn(List.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Active", "manager-1"),
                new UserRecord("manager-1", "manager@example.com", "Mgr", "One", "MANAGER", "Active", null),
                new UserRecord("admin-1", "admin@example.com", "Admin", "One", "ADMIN", "Active", null),
                new UserRecord("inactive-1", "inactive@example.com", "Inactive", "One", "EMPLOYEE", "Inactive", null)));
        when(domain.parseDate("2026-05-19")).thenReturn(java.time.LocalDate.parse("2026-05-19"));
        when(domain.parseDate("2026-05-25")).thenReturn(java.time.LocalDate.parse("2026-05-25"));
        when(domain.parseDate("2026-06-01")).thenReturn(java.time.LocalDate.parse("2026-06-01"));
        when(domain.parseDate("2026-06-05")).thenReturn(java.time.LocalDate.parse("2026-06-05"));
        when(domain.parseDate("2026-06-03")).thenReturn(java.time.LocalDate.parse("2026-06-03"));
        when(domain.parseDate("2026-06-04")).thenReturn(java.time.LocalDate.parse("2026-06-04"));
        when(jobs.createPending(anyString(), anyString(), anyString(), anyLong(), any()))
                .thenReturn(true);
        when(jobs.getStatus(anyString())).thenReturn(status("PENDING"));

        int created = service.enqueueAutoDeadlineReminders(java.time.LocalDate.parse("2026-05-19"));

        assertEquals(2, created);
        ArgumentCaptor<String> jobIds = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(jobs, times(2)).createPending(jobIds.capture(), eq("AUTO_DEADLINE_REMINDER_DIGEST"),
                json.capture(), anyLong(), any());
        assertTrue(jobIds.getAllValues().contains("AUTO_DIGEST#2026-05-19#cycle-1#employee-1"));
        assertTrue(jobIds.getAllValues().contains("AUTO_DIGEST#2026-05-19#cycle-1#manager-1"));
        AutoDeadlineReminderDigestRequest payload = service.mapper.readValue(
                json.getAllValues().get(0), AutoDeadlineReminderDigestRequest.class);
        assertEquals("cycle-1", payload.cycleId());
        assertEquals(1, payload.reminders().size());
        assertEquals("CYCLE_START", payload.reminders().get(0).deadlineEvent());
    }

    @Test
    void enqueueAutoDeadlineReminders_groupsMilestoneEventsByRecipient() throws Exception {
        when(domain.cyclesForStatus("SCHEDULED")).thenReturn(List.of());
        when(domain.cyclesForStatus("ACTIVE")).thenReturn(List.of(
                new CycleRecord("cycle-1", "Cycle", "ACTIVE", "2026-06-01", "2026-06-30",
                        List.of(
                                new MilestoneRecord("m1", "Self Review", "2026-05-19", "2026-05-19", "OPEN", "SELF", List.of("EMPLOYEE")),
                                new MilestoneRecord("m2", "Manager Review", "2026-05-19", "2026-05-19", "OPEN", "MANAGER", List.of("MANAGER"))))));
        when(domain.activeUsers()).thenReturn(List.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Active", "manager-1"),
                new UserRecord("manager-1", "manager@example.com", "Mgr", "One", "MANAGER", "Active", null),
                new UserRecord("admin-1", "admin@example.com", "Admin", "One", "ADMIN", "Active", null)));
        when(domain.parseDate("2026-05-19")).thenReturn(java.time.LocalDate.parse("2026-05-19"));
        when(domain.parseDate("2026-06-01")).thenReturn(java.time.LocalDate.parse("2026-06-01"));
        when(domain.parseDate("2026-06-30")).thenReturn(java.time.LocalDate.parse("2026-06-30"));
        when(jobs.createPending(anyString(), eq("AUTO_DEADLINE_REMINDER_DIGEST"), anyString(), anyLong(), any()))
                .thenReturn(true);
        when(jobs.getStatus(anyString())).thenReturn(status("PENDING"));

        int created = service.enqueueAutoDeadlineReminders(java.time.LocalDate.parse("2026-05-19"));

        assertEquals(2, created);
        ArgumentCaptor<String> jobIds = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(jobs, times(2)).createPending(jobIds.capture(), eq("AUTO_DEADLINE_REMINDER_DIGEST"),
                json.capture(), anyLong(), any());
        assertTrue(jobIds.getAllValues().contains("AUTO_DIGEST#2026-05-19#cycle-1#employee-1"));
        assertTrue(jobIds.getAllValues().contains("AUTO_DIGEST#2026-05-19#cycle-1#manager-1"));
        AutoDeadlineReminderDigestRequest payload = service.mapper.readValue(
                json.getAllValues().get(0), AutoDeadlineReminderDigestRequest.class);
        assertEquals("employee-1", payload.recipientUserId());
        assertEquals("cycle-1", payload.cycleId());
        assertEquals(2, payload.reminders().size());
        assertEquals("MILESTONE_START", payload.reminders().get(0).deadlineEvent());
        assertEquals("MILESTONE_END", payload.reminders().get(1).deadlineEvent());
        assertEquals("m1", payload.reminders().get(0).scopeId());
    }

    @Test
    void enqueueAutoDeadlineReminders_dedupesRecipientsByCycleDate() {
        when(domain.cyclesForStatus("SCHEDULED")).thenReturn(List.of());
        when(domain.cyclesForStatus("ACTIVE")).thenReturn(List.of(
                new CycleRecord("cycle-1", "Cycle", "ACTIVE", "2026-05-19", "2026-05-19",
                        List.of(
                                new MilestoneRecord("m1", "Self Review", "2026-06-01", "2026-06-02", "OPEN", "SELF", List.of("EMPLOYEE")),
                                new MilestoneRecord("m2", "Peer Review", "2026-06-03", "2026-06-04", "OPEN", "PEER_360", List.of("EMPLOYEE"))))));
        when(domain.activeUsers()).thenReturn(List.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Active", "manager-1")));
        when(domain.parseDate("2026-05-19")).thenReturn(java.time.LocalDate.parse("2026-05-19"));
        when(domain.parseDate("2026-06-01")).thenReturn(java.time.LocalDate.parse("2026-06-01"));
        when(domain.parseDate("2026-06-02")).thenReturn(java.time.LocalDate.parse("2026-06-02"));
        when(domain.parseDate("2026-06-03")).thenReturn(java.time.LocalDate.parse("2026-06-03"));
        when(domain.parseDate("2026-06-04")).thenReturn(java.time.LocalDate.parse("2026-06-04"));
        when(jobs.createPending(anyString(), eq("AUTO_DEADLINE_REMINDER_DIGEST"), anyString(), anyLong(), any()))
                .thenReturn(true, false);
        when(jobs.getStatus(anyString())).thenReturn(status("PENDING"), status("COMPLETED"));

        int first = service.enqueueAutoDeadlineReminders(java.time.LocalDate.parse("2026-05-19"));
        int second = service.enqueueAutoDeadlineReminders(java.time.LocalDate.parse("2026-05-19"));

        assertEquals(1, first);
        assertEquals(0, second);
        ArgumentCaptor<String> jobIds = ArgumentCaptor.forClass(String.class);
        verify(jobs, times(2)).createPending(jobIds.capture(), eq("AUTO_DEADLINE_REMINDER_DIGEST"), anyString(), anyLong(), any());
        assertEquals(jobIds.getAllValues().get(0), jobIds.getAllValues().get(1));
        assertTrue(jobIds.getAllValues().get(0).startsWith("AUTO_DIGEST#2026-05-19#cycle-1#employee-1"));
    }

    @Test
    void enqueueAutoDeadlineReminders_usesSeparateStartAndEndOffsets() throws Exception {
        when(domain.cyclesForStatus("SCHEDULED")).thenReturn(List.of());
        when(domain.cyclesForStatus("ACTIVE")).thenReturn(List.of(
                new CycleRecord("cycle-1", "Cycle", "ACTIVE", "2026-05-20", "2026-06-30",
                        List.of(
                                new MilestoneRecord("m1", "Self Review", "2026-06-01", "2026-05-20", "OPEN", "SELF", List.of("EMPLOYEE"))))));
        when(domain.activeUsers()).thenReturn(List.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Active", "manager-1")));
        when(domain.parseDate("2026-05-20")).thenReturn(java.time.LocalDate.parse("2026-05-20"));
        when(domain.parseDate("2026-06-30")).thenReturn(java.time.LocalDate.parse("2026-06-30"));
        when(domain.parseDate("2026-06-01")).thenReturn(java.time.LocalDate.parse("2026-06-01"));
        when(jobs.createPending(anyString(), eq("AUTO_DEADLINE_REMINDER_DIGEST"), anyString(), anyLong(), any()))
                .thenReturn(true);
        when(jobs.getStatus(anyString())).thenReturn(status("PENDING"));

        int created = service.enqueueAutoDeadlineReminders(java.time.LocalDate.parse("2026-05-19"));

        assertEquals(1, created);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(jobs).createPending(eq("AUTO_DIGEST#2026-05-19#cycle-1#employee-1"),
                eq("AUTO_DEADLINE_REMINDER_DIGEST"), json.capture(), anyLong(), any());
        AutoDeadlineReminderDigestRequest payload = service.mapper.readValue(
                json.getValue(), AutoDeadlineReminderDigestRequest.class);
        assertEquals(1, payload.reminders().size());
        assertEquals("MILESTONE_END", payload.reminders().get(0).deadlineEvent());
    }

    @Test
    void enqueueAutoDeadlineReminders_usesFourteenDayEndOffset() throws Exception {
        when(domain.cyclesForStatus("SCHEDULED")).thenReturn(List.of());
        when(domain.cyclesForStatus("ACTIVE")).thenReturn(List.of(
                new CycleRecord("cycle-1", "Cycle", "ACTIVE", "2026-06-01", "2026-06-02",
                        List.of(
                                new MilestoneRecord("m1", "Self Review", "2026-06-10", "2026-06-11", "OPEN", "SELF", List.of("EMPLOYEE"))))));
        when(domain.activeUsers()).thenReturn(List.of(
                new UserRecord("employee-1", "employee@example.com", "Emp", "One", "EMPLOYEE", "Active", "manager-1")));
        when(domain.parseDate("2026-06-01")).thenReturn(java.time.LocalDate.parse("2026-06-01"));
        when(domain.parseDate("2026-06-02")).thenReturn(java.time.LocalDate.parse("2026-06-02"));
        when(domain.parseDate("2026-06-10")).thenReturn(java.time.LocalDate.parse("2026-06-10"));
        when(domain.parseDate("2026-06-11")).thenReturn(java.time.LocalDate.parse("2026-06-11"));
        when(jobs.createPending(anyString(), eq("AUTO_DEADLINE_REMINDER_DIGEST"), anyString(), anyLong(), any()))
                .thenReturn(true);
        when(jobs.getStatus(anyString())).thenReturn(status("PENDING"));

        int created = service.enqueueAutoDeadlineReminders(java.time.LocalDate.parse("2026-05-19"));

        assertEquals(1, created);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(jobs).createPending(eq("AUTO_DIGEST#2026-05-19#cycle-1#employee-1"),
                eq("AUTO_DEADLINE_REMINDER_DIGEST"), json.capture(), anyLong(), any());
        AutoDeadlineReminderDigestRequest payload = service.mapper.readValue(
                json.getValue(), AutoDeadlineReminderDigestRequest.class);
        assertEquals(1, payload.reminders().size());
        assertEquals("CYCLE_END", payload.reminders().get(0).deadlineEvent());
    }

    @Test
    void getStatus_invalidUnknownJobReturnsNull() {
        when(jobs.getStatus("random-job")).thenReturn(null);

        assertNull(service.getStatus("random-job"));
    }

    private NotificationJobStatusResponse status(String status) {
        NotificationJobStatusResponse response = new NotificationJobStatusResponse();
        response.setStatus(NotificationJobStatus.fromValue(status));
        return response;
    }
}

package com.catalystflow.notification.service;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

public final class NotificationPayloads {
    private NotificationPayloads() {}

    @RegisterForReflection
    public record NotificationIntent(String type, String sendDate, String cycleId,
                                     String subjectUserId, String publishedBy) {}

    @RegisterForReflection
    public record AutoDeadlineReminderDigestRequest(String recipientUserId,
                                                    String cycleId,
                                                    String cycleTitle,
                                                    String sendDate,
                                                    List<AutoDeadlineReminderDigestItem> reminders) {}

    @RegisterForReflection
    public record AutoDeadlineReminderDigestItem(String deadlineEvent,
                                                 String targetDate,
                                                 String scopeId,
                                                 String milestoneId,
                                                 String milestoneName) {}

    @RegisterForReflection
    public record ManualReminderJobRequest(String recipientUserId, String reviewId,
                                           String cycleId, String subjectUserId,
                                           String targetType, String requestedBy,
                                           String message, String sendDate) {}

    @RegisterForReflection
    public record ReviewPublishedRequest(String recipientUserId, String cycleId,
                                         String subjectUserId, String publishedBy) {}

    @RegisterForReflection
    public record ReviewRecord(String reviewId, String cycleId, String templateId,
                               String subjectUserId, String authorUserId,
                               String reviewType, String status) {}

    @RegisterForReflection
    public record UserRecord(String userId, String email, String firstName,
                             String lastName, String role, String status,
                             String reportingTo) {}

    @RegisterForReflection
    public record CycleRecord(String cycleId, String cycleTitle, String status,
                              String startDate, String dueDate,
                              List<MilestoneRecord> milestones) {}

    @RegisterForReflection
    public record MilestoneRecord(String milestoneId, String name, String startDate,
                                  String endDate, String status, String reviewType,
                                  List<String> assignedRoles) {
        public MilestoneRecord(String milestoneId, String name, String startDate,
                               String endDate, String status, String reviewType) {
            this(milestoneId, name, startDate, endDate, status, reviewType, List.of());
        }
    }

    @RegisterForReflection
    public record MaintenanceResult(int cyclesUpdated, int milestonesUpdated) {}
}

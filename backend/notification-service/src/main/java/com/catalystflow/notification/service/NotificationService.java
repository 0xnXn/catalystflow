package com.catalystflow.notification.service;

import com.catalystflow.notification.model.*;
import com.catalystflow.notification.repository.NotificationJobRepository;
import com.catalystflow.notification.service.NotificationPayloads.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@ApplicationScoped
public class NotificationService {

    private static final Logger LOG = Logger.getLogger(NotificationService.class);
    private static final ZoneId AU_ZONE = ZoneId.of("Australia/Sydney");
    private static final Set<String> INCOMPLETE = Set.of("NOT_STARTED", "DRAFT", "IN_PROGRESS");
    private static final long SECONDS_PER_DAY = 86_400L;

    @Inject
    NotificationJobRepository jobs;

    @Inject
    NotificationDomainLookup domain;

    @Inject
    ObjectMapper mapper;

    @Inject
    SqsClient sqs;

    @ConfigProperty(name = "notification-jobs.queue-url")
    Optional<String> jobsQueueUrl;

    @ConfigProperty(name = "notification.start-reminder-offsets-days")
    String startReminderOffsets;

    @ConfigProperty(name = "notification.end-reminder-offsets-days")
    String endReminderOffsets;

    @ConfigProperty(name = "notification.job-ttl-days")
    long jobTtlDays;

    public ManualReminderResponse sendManualReminder(ManualReminderRequest request,
                                                     String callerId,
                                                     boolean admin,
                                                     boolean manager) {
        if (!admin && !manager) throw new ForbiddenException("Manager or admin role required");
        validateManualRequest(request);

        LocalDate sendDate = LocalDate.now(AU_ZONE);
        List<NotificationJobSummary> created = new ArrayList<>();
        List<ManualReminderSkippedItem> skipped = new ArrayList<>();

        for (String subjectUserId : request.getSubjectUserIds()) {
            if (subjectUserId == null || subjectUserId.isBlank()) continue;
            List<ReviewRecord> candidates = resolveManualCandidates(request, subjectUserId, callerId, admin);
            if (candidates.isEmpty()) {
                skipped.add(skipped(null, null, null, "NO_REVIEW_FOUND", null));
                continue;
            }

            for (ReviewRecord review : candidates) {
                String recipientUserId = recipientFor(request.getTargetType(), review);
                if (!admin
                        && "MANAGER_REVIEW_MANAGER".equals(request.getTargetType().toString())
                        && !isDirectReport(callerId, recipientUserId)) {
                    skipped.add(skipped(null, review.reviewId(), recipientUserId, "FORBIDDEN", null));
                    continue;
                }
                if (!INCOMPLETE.contains(review.status())) {
                    skipped.add(skipped(null, review.reviewId(), recipientUserId, "NOT_REMINDABLE", null));
                    continue;
                }

                String jobId = manualJobId(sendDate, request.getTargetType().toString(),
                        review.cycleId(), review.subjectUserId(), recipientUserId);
                ManualReminderJobRequest payload = new ManualReminderJobRequest(
                        recipientUserId, review.reviewId(), review.cycleId(), review.subjectUserId(),
                        request.getTargetType().toString(), callerId, request.getMessage(), sendDate.toString());
                CreateResult result = createAndEnqueue(jobId, "MANUAL_REMINDER", payload);
                if (result.created()) {
                    created.add(summary(jobId, "MANUAL_REMINDER", "PENDING", review.reviewId(), recipientUserId, true));
                } else {
                    skipped.add(skipped(jobId, review.reviewId(), recipientUserId,
                            "ALREADY_REMINDED_TODAY", result.status()));
                }
            }
        }

        ManualReminderResponse response = new ManualReminderResponse();
        response.setCreated(created);
        response.setSkipped(skipped);
        return response;
    }

    public void handleIntent(NotificationIntent intent) {
        if (intent == null || intent.type() == null) {
            LOG.warn("Dropping malformed notification intent");
            return;
        }
        switch (intent.type()) {
            case "AUTO_DEADLINE_REMINDER_SCAN" -> enqueueAutoDeadlineReminders(
                    intent.sendDate() == null ? LocalDate.now(AU_ZONE) : LocalDate.parse(intent.sendDate()));
            case "REVIEW_PUBLISHED" -> enqueueReviewPublished(
                    intent.cycleId(), intent.subjectUserId(), intent.publishedBy());
            default -> LOG.warnf("Dropping unknown notification intent type: %s", intent.type());
        }
    }

    public MaintenanceResult runDailyMaintenance(LocalDate today) {
        LocalDate effectiveDate = today == null ? LocalDate.now(AU_ZONE) : today;
        MaintenanceResult maintenance = domain.syncCycleStatuses(effectiveDate);
        int remindersCreated = enqueueAutoDeadlineReminders(effectiveDate);
        LOG.infof("Notification daily maintenance complete for %s. Cycles updated: %d, milestones updated: %d, reminder jobs created: %d",
                effectiveDate, maintenance.cyclesUpdated(), maintenance.milestonesUpdated(), remindersCreated);
        return maintenance;
    }

    public int enqueueAutoDeadlineReminders(LocalDate sendDate) {
        Map<String, List<UserRecord>> activeUsersByRole = activeUsersByRole();
        Map<AutoDigestKey, AutoDigestAccumulator> digests = new LinkedHashMap<>();
        List<CycleRecord> cycles = new ArrayList<>();
        cycles.addAll(domain.cyclesForStatus("SCHEDULED"));
        cycles.addAll(domain.cyclesForStatus("ACTIVE"));

        for (CycleRecord cycle : cycles) {
            List<MilestoneRecord> milestones = Optional.ofNullable(cycle.milestones()).orElse(List.of());
            List<UserRecord> cycleRecipients = recipientsForRoles(
                    milestones.stream()
                            .flatMap(m -> Optional.ofNullable(m.assignedRoles()).orElse(List.of()).stream())
                            .toList(),
                    activeUsersByRole);

            collectForDigest(sendDate, cycle, null, cycleRecipients,
                    "CYCLE_START", cycle.startDate(), digests);
            collectForDigest(sendDate, cycle, null, cycleRecipients,
                    "CYCLE_END", cycle.dueDate(), digests);

            for (MilestoneRecord milestone : Optional.ofNullable(cycle.milestones()).orElse(List.of())) {
                List<UserRecord> milestoneRecipients = recipientsForRoles(milestone.assignedRoles(), activeUsersByRole);
                collectForDigest(sendDate, cycle, milestone, milestoneRecipients,
                        "MILESTONE_START", milestone.startDate(), digests);
                collectForDigest(sendDate, cycle, milestone, milestoneRecipients,
                        "MILESTONE_END", milestone.endDate(), digests);
            }
        }

        int created = 0;
        for (AutoDigestAccumulator digest : digests.values()) {
            String jobId = autoDigestJobId(sendDate, digest.cycle().cycleId(), digest.recipient().userId());
            AutoDeadlineReminderDigestRequest payload = new AutoDeadlineReminderDigestRequest(
                    digest.recipient().userId(),
                    digest.cycle().cycleId(),
                    digest.cycle().cycleTitle(),
                    sendDate.toString(),
                    digest.reminders());
            if (createAndEnqueue(jobId, "AUTO_DEADLINE_REMINDER_DIGEST", payload).created()) {
                created++;
            }
        }
        return created;
    }

    public NotificationJobSummary enqueueReviewPublished(String cycleId, String subjectUserId, String publishedBy) {
        if (cycleId == null || cycleId.isBlank() || subjectUserId == null || subjectUserId.isBlank()) {
            throw new BadRequestException("cycleId and subjectUserId are required");
        }
        String jobId = publishedJobId(cycleId, subjectUserId);
        ReviewPublishedRequest payload = new ReviewPublishedRequest(
                subjectUserId, cycleId, subjectUserId, publishedBy);
        CreateResult result = createAndEnqueue(jobId, "REVIEW_PUBLISHED", payload);
        return summary(jobId, "REVIEW_PUBLISHED", result.status(), null, subjectUserId, result.created());
    }

    public NotificationJobStatusResponse getStatus(String jobId) {
        NotificationJobStatusResponse status = jobs.getStatus(jobId);
        if (status != null) return status;
        if (!isValidDeterministicJobId(jobId)) return null;

        NotificationJobStatusResponse pending = new NotificationJobStatusResponse();
        pending.setJobId(jobId);
        pending.setTaskType(NotificationTaskType.fromValue(taskTypeForJobId(jobId)));
        pending.setStatus(NotificationJobStatus.PENDING);
        pending.setAttemptCount(0);
        pending.setMaterialized(false);
        return pending;
    }

    public static String publishedJobId(String cycleId, String subjectUserId) {
        return "PUBLISHED#" + cycleId + "#" + subjectUserId;
    }

    private void collectForDigest(LocalDate sendDate, CycleRecord cycle, MilestoneRecord milestone,
                                  List<UserRecord> recipients, String deadlineEvent, String dateValue,
                                  Map<AutoDigestKey, AutoDigestAccumulator> digests) {
        LocalDate targetDate;
        try {
            targetDate = domain.parseDate(dateValue);
        } catch (Exception e) {
            LOG.warnf(e, "Skipping malformed %s date %s for cycle %s",
                    deadlineEvent, dateValue, cycle.cycleId());
            return;
        }
        if (targetDate == null || !matchesOffset(sendDate, targetDate, offsetsFor(deadlineEvent))) return;

        String scopeId = milestone == null ? "CYCLE" : milestone.milestoneId();
        if (scopeId == null || scopeId.isBlank()) {
            LOG.warnf("Skipping %s reminder for cycle %s because milestoneId is missing",
                    deadlineEvent, cycle.cycleId());
            return;
        }

        AutoDeadlineReminderDigestItem item = new AutoDeadlineReminderDigestItem(
                deadlineEvent,
                targetDate.toString(),
                scopeId,
                milestone == null ? null : milestone.milestoneId(),
                milestone == null ? null : milestone.name());
        for (UserRecord recipient : recipients) {
            AutoDigestKey key = new AutoDigestKey(sendDate.toString(), cycle.cycleId(), recipient.userId());
            AutoDigestAccumulator digest = digests.computeIfAbsent(key,
                    ignored -> new AutoDigestAccumulator(recipient, cycle, new ArrayList<>()));
            digest.reminders().add(item);
        }
    }

    private List<ReviewRecord> resolveManualCandidates(ManualReminderRequest request, String subjectUserId,
                                                       String callerId, boolean admin) {
        String target = request.getTargetType().toString();
        if (!admin && ("SELF_REVIEW_SUBJECT".equals(target) || "PEER_REVIEW_EMPLOYEE".equals(target))) {
            requireDirectReport(callerId, subjectUserId);
        }

        List<ReviewRecord> reviews = domain.reviewsForCycle(request.getCycleId()).stream()
                .filter(r -> subjectUserId.equals(r.subjectUserId()))
                .toList();

        if ("SELF_REVIEW_SUBJECT".equals(target)) {
            Optional<UserRecord> subjectUser = domain.user(subjectUserId);
            if (subjectUser.isEmpty() || !"Active".equalsIgnoreCase(subjectUser.get().status())) {
                return List.of();
            }

            List<ReviewRecord> selfReviews = reviews.stream()
                    .filter(r -> "SELF".equals(r.reviewType()))
                    .toList();
            Optional<ReviewRecord> finishedSelfReview = selfReviews.stream()
                    .filter(r -> !INCOMPLETE.contains(r.status()))
                    .findFirst();
            if (finishedSelfReview.isPresent()) {
                return List.of(finishedSelfReview.get());
            }
            Optional<ReviewRecord> incompleteSelfReview = selfReviews.stream().findFirst();
            if (incompleteSelfReview.isPresent()) {
                return List.of(incompleteSelfReview.get());
            }
            // A missing SELF row means the employee has not started yet. It is
            // still remindable, but there is no concrete reviewId to carry.
            return List.of(new ReviewRecord(null, request.getCycleId(), null,
                    subjectUserId, subjectUserId, "SELF", "NOT_STARTED"));
        }
        if ("PEER_REVIEW_EMPLOYEE".equals(target)) {
            // Direct-report authorization is based on the subject employee. The
            // assigned peer reviewer may sit anywhere in the org, because the
            // manager is chasing work for their own employee's review bundle.
            return reviews.stream().filter(r -> "PEER_360".equals(r.reviewType())).toList();
        }
        if ("MANAGER_REVIEW_MANAGER".equals(target)) {
            List<ReviewRecord> managerReviews = reviews.stream()
                    .filter(r -> "MANAGER".equals(r.reviewType()))
                    .toList();
            if (!managerReviews.isEmpty()) {
                return managerReviews;
            }

            Optional<UserRecord> subjectUser = domain.user(subjectUserId);
            if (subjectUser.isEmpty() || !"Active".equalsIgnoreCase(subjectUser.get().status())) {
                return List.of();
            }

            String managerId = subjectUser.get().reportingTo();
            if (managerId == null || managerId.isBlank()) {
                return List.of();
            }

            Optional<UserRecord> managerUser = domain.user(managerId);
            if (managerUser.isEmpty() || !"Active".equalsIgnoreCase(managerUser.get().status())) {
                return List.of();
            }

            // A missing MANAGER row means the manager has not started yet. It is
            // still remindable, but there is no concrete reviewId to carry.
            return List.of(new ReviewRecord(null, request.getCycleId(), null,
                    subjectUserId, managerId, "MANAGER", "NOT_STARTED"));
        }
        throw new BadRequestException("Unsupported targetType: " + target);
    }

    private String recipientFor(ManualReminderTargetType targetType, ReviewRecord review) {
        return switch (targetType.toString()) {
            case "SELF_REVIEW_SUBJECT" -> review.subjectUserId();
            case "PEER_REVIEW_EMPLOYEE", "MANAGER_REVIEW_MANAGER" -> review.authorUserId();
            default -> throw new BadRequestException("Unsupported targetType: " + targetType);
        };
    }

    private void validateManualRequest(ManualReminderRequest request) {
        if (request == null) throw new BadRequestException("Request body is required");
        if (request.getCycleId() == null || request.getCycleId().isBlank()) {
            throw new BadRequestException("cycleId is required");
        }
        if (request.getTargetType() == null) throw new BadRequestException("targetType is required");
        if (request.getSubjectUserIds() == null || request.getSubjectUserIds().isEmpty()) {
            throw new BadRequestException("subjectUserIds is required");
        }
        if (request.getMessage() != null && request.getMessage().length() > 1000) {
            throw new BadRequestException("message must be 1000 characters or less");
        }
    }

    private void requireDirectReport(String callerId, String subjectUserId) {
        if (!isDirectReport(callerId, subjectUserId)) {
            throw new ForbiddenException("Managers can only send reminders for direct reports");
        }
    }

    private boolean isDirectReport(String callerId, String userId) {
        return domain.user(userId)
                .map(UserRecord::reportingTo)
                .map(callerId::equals)
                .orElse(false);
    }

    private String offsetsFor(String deadlineEvent) {
        return deadlineEvent != null && deadlineEvent.endsWith("_START")
                ? startReminderOffsets
                : endReminderOffsets;
    }

    private boolean matchesOffset(LocalDate sendDate, LocalDate targetDate, String offsets) {
        if (offsets == null || offsets.isBlank()) return false;
        for (String part : offsets.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            int offset = Integer.parseInt(trimmed);
            if (targetDate.minusDays(offset).equals(sendDate)) return true;
        }
        return false;
    }

    private CreateResult createAndEnqueue(String jobId, String taskType, Object payload) {
        Instant now = Instant.now();
        String requestJson;
        try {
            requestJson = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize notification payload", e);
        }

        boolean created = jobs.createPending(jobId, taskType, requestJson,
                now.getEpochSecond() + jobTtlDays * SECONDS_PER_DAY, now);
        if (created) enqueueDelivery(jobId);
        NotificationJobStatusResponse existing = jobs.getStatus(jobId);
        String status = existing == null || existing.getStatus() == null
                ? "PENDING"
                : existing.getStatus().toString();
        return new CreateResult(created, status);
    }

    private void enqueueDelivery(String jobId) {
        if (jobsQueueUrl.isEmpty()) {
            LOG.warnf("notification-jobs.queue-url not set; job %s created but not enqueued", jobId);
            return;
        }
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(jobsQueueUrl.get())
                .messageBody(jobId)
                .build());
    }

    private Map<String, List<UserRecord>> activeUsersByRole() {
        Map<String, List<UserRecord>> usersByRole = new HashMap<>();
        for (UserRecord user : Optional.ofNullable(domain.activeUsers()).orElse(List.of())) {
            if (user.userId() == null || user.userId().isBlank()) continue;
            if (!"Active".equalsIgnoreCase(user.status())) continue;
            if (user.role() == null || user.role().isBlank()) continue;
            usersByRole.computeIfAbsent(user.role(), ignored -> new ArrayList<>()).add(user);
        }
        return usersByRole;
    }

    private List<UserRecord> recipientsForRoles(List<String> roles, Map<String, List<UserRecord>> activeUsersByRole) {
        if (roles == null || roles.isEmpty()) return List.of();
        Map<String, UserRecord> recipients = new LinkedHashMap<>();
        for (String role : roles) {
            if (role == null || role.isBlank()) continue;
            for (UserRecord user : activeUsersByRole.getOrDefault(role, List.of())) {
                recipients.putIfAbsent(user.userId(), user);
            }
        }
        return new ArrayList<>(recipients.values());
    }

    private static String autoDigestJobId(LocalDate sendDate, String cycleId, String recipientUserId) {
        return "AUTO_DIGEST#" + sendDate + "#" + cycleId + "#" + recipientUserId;
    }

    private static String manualJobId(LocalDate sendDate, String targetType, String cycleId,
                                      String subjectUserId, String recipientUserId) {
        // Manual reminder dedupe follows the business target rather than
        // reviewId because employees with no SELF row have no reviewId yet.
        return "MANUAL#" + sendDate + "#" + targetType + "#"
                + cycleId + "#" + subjectUserId + "#" + recipientUserId;
    }

    private static boolean isValidDeterministicJobId(String jobId) {
        return jobId != null
                && (jobId.startsWith("AUTO_DIGEST#") || jobId.startsWith("MANUAL#") || jobId.startsWith("PUBLISHED#"));
    }

    private static String taskTypeForJobId(String jobId) {
        if (jobId.startsWith("AUTO_DIGEST#")) return "AUTO_DEADLINE_REMINDER_DIGEST";
        if (jobId.startsWith("MANUAL#")) return "MANUAL_REMINDER";
        return "REVIEW_PUBLISHED";
    }

    private static NotificationJobSummary summary(String jobId, String taskType, String status,
                                                  String reviewId, String recipientUserId,
                                                  boolean materialized) {
        NotificationJobSummary summary = new NotificationJobSummary();
        summary.setJobId(jobId);
        summary.setTaskType(NotificationTaskType.fromValue(taskType));
        summary.setStatus(NotificationJobStatus.fromValue(status));
        summary.setReviewId(reviewId);
        summary.setRecipientUserId(recipientUserId);
        summary.setMaterialized(materialized);
        return summary;
    }

    private static ManualReminderSkippedItem skipped(String jobId, String reviewId,
                                                     String recipientUserId, String reason,
                                                     String status) {
        ManualReminderSkippedItem item = new ManualReminderSkippedItem();
        item.setJobId(jobId);
        item.setReviewId(reviewId);
        item.setRecipientUserId(recipientUserId);
        item.setReason(ManualReminderSkippedItem.ReasonEnum.fromValue(reason));
        if (status != null) item.setStatus(NotificationJobStatus.fromValue(status));
        return item;
    }

    private record CreateResult(boolean created, String status) {}
    private record AutoDigestKey(String sendDate, String cycleId, String recipientUserId) {}
    private record AutoDigestAccumulator(UserRecord recipient,
                                         CycleRecord cycle,
                                         List<AutoDeadlineReminderDigestItem> reminders) {}
}

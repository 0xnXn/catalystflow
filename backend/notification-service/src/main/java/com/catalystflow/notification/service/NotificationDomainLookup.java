package com.catalystflow.notification.service;

import com.catalystflow.notification.service.NotificationPayloads.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class NotificationDomainLookup {

    private static final String GSI_CYCLE = "CycleIndex";
    private static final DateTimeFormatter AU_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Inject
    DynamoDbClient dynamoDb;

    @ConfigProperty(name = "reviews.table-name")
    String reviewsTable;

    @ConfigProperty(name = "review-cycles.table-name")
    String cyclesTable;

    @ConfigProperty(name = "users.table-name")
    String usersTable;

    public Optional<UserRecord> user(String userId) {
        if (userId == null || userId.isBlank()) return Optional.empty();
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(usersTable)
                .key(Map.of("userId", s(userId)))
                .build());
        if (!response.hasItem() || response.item().isEmpty()) return Optional.empty();
        Map<String, AttributeValue> item = response.item();
        return Optional.of(new UserRecord(
                str(item, "userId"),
                str(item, "email"),
                str(item, "firstName"),
                str(item, "lastName"),
                str(item, "role"),
                str(item, "status"),
                str(item, "reportingTo")
        ));
    }

    public List<UserRecord> activeUsers() {
        ScanRequest.Builder builder = ScanRequest.builder()
                .tableName(usersTable)
                .filterExpression("#status = :active")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":active", s("Active")));

        List<UserRecord> users = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            ScanRequest request = startKey == null ? builder.build() : builder.exclusiveStartKey(startKey).build();
            ScanResponse response = dynamoDb.scan(request);
            response.items().stream()
                    .map(this::toUser)
                    .forEach(users::add);
            startKey = response.lastEvaluatedKey();
        } while (startKey != null && !startKey.isEmpty());
        return users;
    }

    public Optional<ReviewRecord> review(String reviewId) {
        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(reviewsTable)
                .key(Map.of("reviewId", s(reviewId)))
                .build());
        if (!response.hasItem() || response.item().isEmpty()) return Optional.empty();
        return Optional.of(toReview(response.item()));
    }

    private UserRecord toUser(Map<String, AttributeValue> item) {
        return new UserRecord(
                str(item, "userId"),
                str(item, "email"),
                str(item, "firstName"),
                str(item, "lastName"),
                str(item, "role"),
                str(item, "status"),
                str(item, "reportingTo")
        );
    }

    public List<ReviewRecord> reviewsForCycle(String cycleId) {
        QueryRequest.Builder builder = QueryRequest.builder()
                .tableName(reviewsTable)
                .indexName(GSI_CYCLE)
                .keyConditionExpression("cycleId = :cycleId")
                .expressionAttributeValues(Map.of(":cycleId", s(cycleId)));

        List<ReviewRecord> reviews = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            QueryRequest request = startKey == null ? builder.build() : builder.exclusiveStartKey(startKey).build();
            QueryResponse response = dynamoDb.query(request);
            response.items().stream().map(this::toReview).forEach(reviews::add);
            startKey = response.lastEvaluatedKey();
        } while (startKey != null && !startKey.isEmpty());
        return reviews;
    }

    public List<CycleRecord> cyclesForStatus(String status) {
        List<CycleRecord> cycles = new ArrayList<>();
        cycleItemsForStatus(status).stream().map(this::toCycle).forEach(cycles::add);
        return cycles;
    }

    public MaintenanceResult syncCycleStatuses(LocalDate today) {
        List<Map<String, AttributeValue>> candidates = new ArrayList<>();
        candidates.addAll(cycleItemsForStatus("SCHEDULED"));
        candidates.addAll(cycleItemsForStatus("ACTIVE"));

        int cyclesUpdated = 0;
        int milestonesUpdated = 0;

        for (Map<String, AttributeValue> item : candidates) {
            String cycleId = str(item, "cycleId");
            String status = str(item, "status");
            String newStatus = status;
            boolean changed = false;
            boolean milestonesChanged = false;
            int cycleMilestonesUpdated = 0;

            if ("SCHEDULED".equals(newStatus)) {
                LocalDate start = parseDate(str(item, "startDate"));
                if (start != null && !today.isBefore(start)) {
                    newStatus = "ACTIVE";
                    changed = true;
                }
            }

            AttributeValue milestonesAttr = item.get("milestones");
            List<AttributeValue> updatedMilestones = new ArrayList<>();
            if ("ACTIVE".equals(newStatus) && milestonesAttr != null && milestonesAttr.hasL()) {
                for (AttributeValue milestoneValue : milestonesAttr.l()) {
                    if (milestoneValue == null || !milestoneValue.hasM()) {
                        updatedMilestones.add(milestoneValue);
                        continue;
                    }
                    Map<String, AttributeValue> milestone = new HashMap<>(milestoneValue.m());
                    String oldMilestoneStatus = str(milestone, "status");
                    String nextMilestoneStatus = oldMilestoneStatus;

                    if ("NOT_STARTED".equals(nextMilestoneStatus)) {
                        LocalDate milestoneStart = parseDate(str(milestone, "startDate"));
                        if (milestoneStart != null && !today.isBefore(milestoneStart)) {
                            nextMilestoneStatus = "OPEN";
                        }
                    }

                    if ("OPEN".equals(nextMilestoneStatus) || "IN_PROGRESS".equals(nextMilestoneStatus)) {
                        LocalDate milestoneEnd = parseDate(str(milestone, "endDate"));
                        if (milestoneEnd != null && today.isAfter(milestoneEnd)) {
                            nextMilestoneStatus = "COMPLETED";
                        }
                    }

                    if (nextMilestoneStatus != null && !nextMilestoneStatus.equals(oldMilestoneStatus)) {
                        milestone.put("status", s(nextMilestoneStatus));
                        milestonesChanged = true;
                        changed = true;
                        cycleMilestonesUpdated++;
                    }
                    updatedMilestones.add(AttributeValue.builder().m(milestone).build());
                }
            }

            if ("ACTIVE".equals(newStatus)) {
                LocalDate due = parseDate(str(item, "dueDate"));
                if (due != null && today.isAfter(due)) {
                    newStatus = "COMPLETED";
                    changed = true;

                    AttributeValue sourceMilestones = milestonesAttr;
                    List<AttributeValue> source = updatedMilestones.isEmpty() && sourceMilestones != null && sourceMilestones.hasL()
                            ? sourceMilestones.l()
                            : updatedMilestones;
                    updatedMilestones = new ArrayList<>();
                    for (AttributeValue milestoneValue : source) {
                        if (milestoneValue == null || !milestoneValue.hasM()) {
                            updatedMilestones.add(milestoneValue);
                            continue;
                        }
                        Map<String, AttributeValue> milestone = new HashMap<>(milestoneValue.m());
                        String oldMilestoneStatus = str(milestone, "status");
                        if ("OPEN".equals(oldMilestoneStatus) || "IN_PROGRESS".equals(oldMilestoneStatus)) {
                            milestone.put("status", s("COMPLETED"));
                            milestonesChanged = true;
                            cycleMilestonesUpdated++;
                        }
                        updatedMilestones.add(AttributeValue.builder().m(milestone).build());
                    }
                }
            }

            if (changed) {
                updateCycleMaintenanceState(cycleId, newStatus, milestonesChanged ? updatedMilestones : null);
                cyclesUpdated++;
                milestonesUpdated += cycleMilestonesUpdated;
            }
        }

        return new MaintenanceResult(cyclesUpdated, milestonesUpdated);
    }

    private List<Map<String, AttributeValue>> cycleItemsForStatus(String status) {
        QueryRequest.Builder builder = QueryRequest.builder()
                .tableName(cyclesTable)
                .indexName("status-startDate-index")
                .keyConditionExpression("#status = :status")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(":status", s(status)));

        List<Map<String, AttributeValue>> cycles = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            QueryRequest request = startKey == null ? builder.build() : builder.exclusiveStartKey(startKey).build();
            QueryResponse response = dynamoDb.query(request);
            cycles.addAll(response.items());
            startKey = response.lastEvaluatedKey();
        } while (startKey != null && !startKey.isEmpty());
        return cycles;
    }

    private void updateCycleMaintenanceState(String cycleId, String status, List<AttributeValue> milestones) {
        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();
        List<String> assignments = new ArrayList<>();

        names.put("#status", "status");
        values.put(":status", s(status));
        assignments.add("#status = :status");

        if (milestones != null) {
            names.put("#milestones", "milestones");
            values.put(":milestones", AttributeValue.builder().l(milestones).build());
            assignments.add("#milestones = :milestones");
        }

        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(cyclesTable)
                .key(Map.of("cycleId", s(cycleId)))
                .updateExpression("SET " + String.join(", ", assignments))
                .expressionAttributeNames(names)
                .expressionAttributeValues(values)
                .build());
    }

    public LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.contains("-")) return LocalDate.parse(value);
        if (value.matches("\\d{4}/\\d{2}/\\d{2}")) {
            return LocalDate.parse(value, DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        }
        return LocalDate.parse(value, AU_FORMAT);
    }

    private ReviewRecord toReview(Map<String, AttributeValue> item) {
        return new ReviewRecord(
                str(item, "reviewId"),
                str(item, "cycleId"),
                str(item, "templateId"),
                str(item, "subjectUserId"),
                str(item, "authorUserId"),
                str(item, "reviewType"),
                str(item, "status")
        );
    }

    private CycleRecord toCycle(Map<String, AttributeValue> item) {
        List<MilestoneRecord> milestones = new ArrayList<>();
        AttributeValue attr = item.get("milestones");
        if (attr != null && attr.hasL()) {
            for (AttributeValue v : attr.l()) {
                if (v != null && v.hasM()) milestones.add(toMilestone(v.m()));
            }
        }
        return new CycleRecord(
                str(item, "cycleId"),
                str(item, "cycleTitle"),
                str(item, "status"),
                str(item, "startDate"),
                str(item, "dueDate"),
                milestones
        );
    }

    private MilestoneRecord toMilestone(Map<String, AttributeValue> item) {
        String reviewType = null;
        AttributeValue snapshot = item.get("templateSnapshot");
        if (snapshot != null && snapshot.hasM()) {
            reviewType = str(snapshot.m(), "reviewType");
        }
        return new MilestoneRecord(
                str(item, "milestoneId"),
                str(item, "name"),
                str(item, "startDate"),
                str(item, "endDate"),
                str(item, "status"),
                reviewType,
                stringList(item.get("assignedRoles"))
        );
    }

    private static List<String> stringList(AttributeValue value) {
        if (value == null || !value.hasL()) return List.of();
        return value.l().stream()
                .filter(v -> v != null && v.s() != null && !v.s().isBlank())
                .map(AttributeValue::s)
                .toList();
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static String str(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return v == null ? null : v.s();
    }
}

package com.catalystflow.notification.repository;

import com.catalystflow.notification.model.NotificationJobStatus;
import com.catalystflow.notification.model.NotificationJobStatusResponse;
import com.catalystflow.notification.model.NotificationTaskType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class NotificationJobRepository {

    private static final Logger LOG = Logger.getLogger(NotificationJobRepository.class);

    @Inject
    DynamoDbClient dynamoDb;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "notification-jobs.table-name")
    String tableName;

    public boolean createPending(String jobId, String taskType, String requestJson,
                                 long expiresAt, Instant now) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("jobId", s(jobId));
        item.put("status", s("PENDING"));
        item.put("taskType", s(taskType));
        item.put("requestJson", s(requestJson));
        item.put("attemptCount", n(0));
        item.put("expiresAt", n(expiresAt));
        item.put("createdAt", s(now.toString()));
        item.put("updatedAt", s(now.toString()));

        try {
            dynamoDb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .conditionExpression("attribute_not_exists(jobId)")
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            // Deterministic jobId is the dedupe guard. Existing rows mean the
            // notification has already been requested for this business key.
            LOG.infof("Notification job %s already exists; suppressing duplicate", jobId);
            return false;
        }
    }

    public NotificationJobStatusResponse getStatus(String jobId) {
        Map<String, AttributeValue> item = getItem(jobId, null);
        if (item == null) return null;

        NotificationJobStatusResponse response = new NotificationJobStatusResponse();
        response.setJobId(jobId);
        response.setTaskType(NotificationTaskType.fromValue(str(item, "taskType")));
        response.setStatus(NotificationJobStatus.fromValue(str(item, "status")));
        response.setErrorMessage(str(item, "errorMessage"));
        response.setAttemptCount(intVal(item, "attemptCount"));
        response.setMaterialized(true);

        String resultJson = str(item, "resultJson");
        if (resultJson != null && !resultJson.isBlank()) {
            try {
                response.setResult(mapper.readValue(resultJson, new TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                LOG.warnf(e, "Could not parse notification resultJson for %s", jobId);
            }
        }
        return response;
    }

    public JobData getJobData(String jobId) {
        Map<String, AttributeValue> item = getItem(jobId, null);
        if (item == null) return null;
        return new JobData(jobId, str(item, "status"), str(item, "taskType"),
                str(item, "requestJson"), intVal(item, "attemptCount"));
    }

    public boolean tryClaim(String jobId, Instant now) {
        try {
            dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("jobId", s(jobId)))
                    .updateExpression("SET #st = :processing, updatedAt = :updatedAt")
                    .conditionExpression("#st = :pending")
                    .expressionAttributeNames(Map.of("#st", "status"))
                    .expressionAttributeValues(Map.of(
                            ":processing", s("PROCESSING"),
                            ":pending", s("PENDING"),
                            ":updatedAt", s(now.toString())
                    ))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            LOG.infof("Notification job %s was already claimed or is terminal", jobId);
            return false;
        }
    }

    public void markCompleted(String jobId, String resultJson, Instant now) {
        updateTerminal(jobId, "COMPLETED", resultJson, null, now);
    }

    public void markSkipped(String jobId, String message, Instant now) {
        updateTerminal(jobId, "SKIPPED", null, message, now);
    }

    public void markFailed(String jobId, String message, Instant now) {
        updateTerminal(jobId, "FAILED", null, message, now);
    }

    public void markRetryableFailure(String jobId, int attemptCount, String message, Instant now) {
        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("jobId", s(jobId)))
                .updateExpression("SET #st = :pending, attemptCount = :attemptCount, errorMessage = :err, updatedAt = :updatedAt")
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(Map.of(
                        ":pending", s("PENDING"),
                        ":attemptCount", n(attemptCount),
                        ":err", s(message == null ? "Unknown error" : message),
                        ":updatedAt", s(now.toString())
                ))
                .build());
    }

    private void updateTerminal(String jobId, String status, String resultJson,
                                String errorMessage, Instant now) {
        StringBuilder expr = new StringBuilder("SET #st = :status, updatedAt = :updatedAt");
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":status", s(status));
        values.put(":updatedAt", s(now.toString()));
        if (resultJson != null) {
            expr.append(", resultJson = :resultJson");
            values.put(":resultJson", s(resultJson));
        }
        if (errorMessage != null) {
            expr.append(", errorMessage = :err");
            values.put(":err", s(errorMessage));
        }
        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("jobId", s(jobId)))
                .updateExpression(expr.toString())
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(values)
                .build());
    }

    private Map<String, AttributeValue> getItem(String jobId, String projection) {
        GetItemRequest.Builder builder = GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("jobId", s(jobId)))
                .consistentRead(true);
        if (projection != null) builder.projectionExpression(projection);
        GetItemResponse response = dynamoDb.getItem(builder.build());
        return response.hasItem() && !response.item().isEmpty() ? response.item() : null;
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value == null ? "" : value).build();
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(String.valueOf(value)).build();
    }

    private static String str(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return v == null || v.s() == null ? null : v.s();
    }

    private static Integer intVal(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return v == null || v.n() == null ? 0 : Integer.valueOf(v.n());
    }

    public record JobData(String jobId, String status, String taskType,
                          String requestJson, int attemptCount) {}
}

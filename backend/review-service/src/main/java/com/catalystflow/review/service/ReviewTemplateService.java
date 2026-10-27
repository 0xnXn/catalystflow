package com.catalystflow.review.service;

import com.catalystflow.review.model.ReviewTemplate;
import com.catalystflow.review.model.TemplateQuestion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service layer for managing reusable review templates.
 *
 * Template names are globally unique.
 * Template updates do NOT affect milestones with an existing snapshot of the template.
 * usageCount and lastUsedAt are incremented when a snapshot is created from this template.
 */
@ApplicationScoped
public class ReviewTemplateService {

    private static final Logger LOG = Logger.getLogger(ReviewTemplateService.class);

    @Inject
    DynamoDbClient dynamoDb;

    @ConfigProperty(name = "review-templates.table-name")
    String templatesTableName;

    private static final Set<String> ALLOWED_REVIEW_TYPES = Arrays.stream(ReviewTemplate.ReviewTypeEnum.values())
            .map(ReviewTemplate.ReviewTypeEnum::value)
            .collect(Collectors.toUnmodifiableSet());

    private static final Set<String> ALLOWED_QUESTION_TYPES = Set.of(
            "TEXT", "RATING", "CHECKBOX", "SINGLE_SELECT", "MULTI_SELECT"
    );

    // ── Read ──────────────────────────────────────────────────────────────────

    public Optional<ReviewTemplate> getTemplate(String templateId) {
        LOG.infof("Getting template: %s", templateId);

        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(templatesTableName)
                .key(Map.of("templateId", AttributeValue.builder().s(templateId).build()))
                .build());

        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(toTemplate(response.item()));
    }

    /**
     * List all templates. Optionally filter by reviewType using the GSI.
     * Scans the full table when no filter is provided.
     */
    public List<ReviewTemplate> getAllTemplates(String reviewType) {
        if (reviewType != null && !reviewType.isBlank()) {
            if (!ALLOWED_REVIEW_TYPES.contains(reviewType)) {
                throw new IllegalArgumentException(
                        "Invalid reviewType. Allowed: " + ALLOWED_REVIEW_TYPES);
            }

            // Paginate the GSI query so callers see every matching template,
            // not just the first 1MB page.
            List<ReviewTemplate> templates = new ArrayList<>();
            Map<String, AttributeValue> startKey = null;
            QueryRequest.Builder qb = QueryRequest.builder()
                    .tableName(templatesTableName)
                    .indexName("reviewType-createdAt-index")
                    .keyConditionExpression("#rt = :reviewType")
                    .expressionAttributeNames(Map.of("#rt", "reviewType"))
                    .expressionAttributeValues(Map.of(
                            ":reviewType", AttributeValue.builder().s(reviewType).build()
                    ));
            do {
                QueryRequest request = startKey == null
                        ? qb.build()
                        : qb.exclusiveStartKey(startKey).build();
                QueryResponse response = dynamoDb.query(request);
                response.items().stream().map(this::toTemplate).forEach(templates::add);
                startKey = response.lastEvaluatedKey();
            } while (startKey != null && !startKey.isEmpty());
            return templates;
        }

        // Paginate the full-table scan — without LastEvaluatedKey we'd silently
        // truncate to whatever fits in the first 1MB page.
        List<ReviewTemplate> templates = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        ScanRequest.Builder sb = ScanRequest.builder().tableName(templatesTableName);
        do {
            ScanRequest request = startKey == null
                    ? sb.build()
                    : sb.exclusiveStartKey(startKey).build();
            ScanResponse response = dynamoDb.scan(request);
            response.items().stream().map(this::toTemplate).forEach(templates::add);
            startKey = response.lastEvaluatedKey();
        } while (startKey != null && !startKey.isEmpty());
        return templates;
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    public ReviewTemplate createTemplate(ReviewTemplate request) {
        validateCreateRequest(request);

        if (isNameTaken(request.getTemplateName(), null)) {
            throw new IllegalArgumentException(
                    "A template named '" + request.getTemplateName() + "' already exists.");
        }

        UUID templateId = UUID.randomUUID();
        String now = now();

        ReviewTemplate template = new ReviewTemplate();
        template.setTemplateId(templateId);
        template.setTemplateName(request.getTemplateName().trim());
        template.setReviewType(request.getReviewType());
        template.setDescription(request.getDescription());
        template.setQuestions(normalizeQuestions(request.getQuestions()));
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        template.setUsageCount(0);

        saveTemplate(template);
        LOG.infof("Created template: %s (%s)", templateId, template.getTemplateName());
        return template;
    }

    public Optional<ReviewTemplate> updateTemplate(String templateId, ReviewTemplate request) {
        Optional<ReviewTemplate> existingOpt = getTemplate(templateId);
        if (existingOpt.isEmpty()) {
            return Optional.empty();
        }

        ReviewTemplate existing = existingOpt.get();

        if (request.getTemplateName() != null
                && !request.getTemplateName().trim().equalsIgnoreCase(existing.getTemplateName())
                && isNameTaken(request.getTemplateName(), templateId)) {
            throw new IllegalArgumentException(
                    "A template named '" + request.getTemplateName() + "' already exists.");
        }

        if (request.getTemplateName() != null) {
            existing.setTemplateName(request.getTemplateName().trim());
        }
        if (request.getReviewType() != null) {
            if (!ALLOWED_REVIEW_TYPES.contains(request.getReviewType().value())) {
                throw new IllegalArgumentException("Invalid reviewType: " + request.getReviewType());
            }
            existing.setReviewType(request.getReviewType());
        }
        if (request.getDescription() != null) {
            existing.setDescription(request.getDescription());
        }
        if (request.getQuestions() != null && !request.getQuestions().isEmpty()) {
            existing.setQuestions(normalizeQuestions(request.getQuestions()));
        }

        existing.setUpdatedAt(now());
        saveTemplate(existing);

        LOG.infof("Updated template: %s", templateId);
        return Optional.of(existing);
    }

    public boolean deleteTemplate(String templateId) {
        if (getTemplate(templateId).isEmpty()) {
            return false;
        }

        dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(templatesTableName)
                .key(Map.of("templateId", AttributeValue.builder().s(templateId).build()))
                .build());

        LOG.infof("Deleted template: %s", templateId);
        return true;
    }

    /**
     * Increment usageCount and update lastUsedAt.
     * Called when a snapshot of this template is attached to a milestone.
     */
    public void recordUsage(String templateId) {
        String now = now();

        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(templatesTableName)
                .key(Map.of("templateId", AttributeValue.builder().s(templateId).build()))
                .updateExpression("SET lastUsedAt = :now, usageCount = if_not_exists(usageCount, :zero) + :one")
                .expressionAttributeValues(Map.of(
                        ":now",  AttributeValue.builder().s(now).build(),
                        ":zero", AttributeValue.builder().n("0").build(),
                        ":one",  AttributeValue.builder().n("1").build()
                ))
                .build());
    }

    // ── Name uniqueness ───────────────────────────────────────────────────────

    /**
     * Returns true if the given name is already taken by a different template.
     * Pass excludeId to skip the current template during an update check.
     */
    public boolean isNameTaken(String name, String excludeId) {
        if (name == null || name.isBlank()) return false;

        QueryResponse response = dynamoDb.query(QueryRequest.builder()
                .tableName(templatesTableName)
                .indexName("templateName-index")
                .keyConditionExpression("templateName = :name")
                .expressionAttributeValues(Map.of(
                        ":name", AttributeValue.builder().s(name.trim()).build()
                ))
                .build());

        return response.items().stream()
                .anyMatch(item -> {
                    String existingId = attrStr(item, "templateId");
                    return excludeId == null || !excludeId.equals(existingId);
                });
    }

    // ── DynamoDB helpers ──────────────────────────────────────────────────────

    private void saveTemplate(ReviewTemplate template) {
        Map<String, AttributeValue> item = new HashMap<>();

        item.put("templateId",    AttributeValue.builder().s(template.getTemplateId().toString()).build());
        item.put("templateName",  AttributeValue.builder().s(template.getTemplateName()).build());
        item.put("reviewType",    AttributeValue.builder().s(template.getReviewType().value()).build());
        item.put("createdAt",     AttributeValue.builder().s(template.getCreatedAt()).build());
        item.put("updatedAt",     AttributeValue.builder().s(template.getUpdatedAt()).build());
        item.put("usageCount",    AttributeValue.builder().n(String.valueOf(
                template.getUsageCount() != null ? template.getUsageCount() : 0)).build());

        if (template.getDescription() != null && !template.getDescription().isBlank()) {
            item.put("description", AttributeValue.builder().s(template.getDescription()).build());
        }
        if (template.getLastUsedAt() != null && !template.getLastUsedAt().isBlank()) {
            item.put("lastUsedAt", AttributeValue.builder().s(template.getLastUsedAt()).build());
        }
        if (template.getQuestions() != null && !template.getQuestions().isEmpty()) {
            item.put("questions", AttributeValue.builder()
                    .l(writeQuestionsList(template.getQuestions())).build());
        }

        dynamoDb.putItem(PutItemRequest.builder().tableName(templatesTableName).item(item).build());
    }

    private ReviewTemplate toTemplate(Map<String, AttributeValue> item) {
        ReviewTemplate t = new ReviewTemplate();
        t.setTemplateId(parseUuid(attrStr(item, "templateId")));
        t.setTemplateName(attrStr(item, "templateName"));
        String reviewType = attrStr(item, "reviewType");
        if (reviewType != null) {
            t.setReviewType(ReviewTemplate.ReviewTypeEnum.fromValue(reviewType));
        }
        t.setDescription(attrStr(item, "description"));
        t.setCreatedAt(attrStr(item, "createdAt"));
        t.setUpdatedAt(attrStr(item, "updatedAt"));
        t.setLastUsedAt(attrStr(item, "lastUsedAt"));

        AttributeValue usageAttr = item.get("usageCount");
        if (usageAttr != null && usageAttr.n() != null) {
            t.setUsageCount(Integer.valueOf(usageAttr.n()));
        }

        AttributeValue questionsAttr = item.get("questions");
        if (questionsAttr != null && questionsAttr.hasL()) {
            t.setQuestions(readQuestionsFromList(questionsAttr.l()));
        }

        return t;
    }

    private List<AttributeValue> writeQuestionsList(List<TemplateQuestion> questions) {
        List<AttributeValue> result = new ArrayList<>();
        for (TemplateQuestion q : questions) {
            result.add(AttributeValue.builder().m(writeQuestionMap(q)).build());
        }
        return result;
    }

    private Map<String, AttributeValue> writeQuestionMap(TemplateQuestion q) {
        Map<String, AttributeValue> result = new HashMap<>();
        if (q.getQuestionId() != null) result.put("questionId", AttributeValue.builder().s(q.getQuestionId().toString()).build());
        if (q.getText() != null)       result.put("text",       AttributeValue.builder().s(q.getText()).build());
        if (q.getType() != null)       result.put("type",       AttributeValue.builder().s(q.getType().value()).build());
        if (q.getRequired() != null)   result.put("required",   AttributeValue.builder().bool(q.getRequired()).build());
        if (q.getOrder() != null)      result.put("order",      AttributeValue.builder().n(String.valueOf(q.getOrder())).build());
        if (q.getIdealResponse() != null && !q.getIdealResponse().isBlank()) {
            result.put("idealResponse", AttributeValue.builder().s(q.getIdealResponse()).build());
        }
        if (q.getOptions() != null && !q.getOptions().isEmpty()) {
            result.put("options", AttributeValue.builder()
                    .l(q.getOptions().stream().map(o -> AttributeValue.builder().s(o).build()).toList())
                    .build());
        }
        return result;
    }

    private List<TemplateQuestion> readQuestionsFromList(List<AttributeValue> values) {
        List<TemplateQuestion> questions = new ArrayList<>();
        for (AttributeValue value : values) {
            if (value != null && value.hasM()) {
                questions.add(readQuestionFromMap(value.m()));
            }
        }
        return questions;
    }

    private TemplateQuestion readQuestionFromMap(Map<String, AttributeValue> map) {
        TemplateQuestion q = new TemplateQuestion();
        q.setQuestionId(parseUuid(attrStr(map, "questionId")));
        q.setText(attrStr(map, "text"));
        String type = attrStr(map, "type");
        if (type != null) {
            q.setType(TemplateQuestion.TypeEnum.fromValue(type));
        }
        q.setIdealResponse(attrStr(map, "idealResponse"));

        AttributeValue requiredAttr = map.get("required");
        if (requiredAttr != null && requiredAttr.bool() != null) {
            q.setRequired(requiredAttr.bool());
        }
        AttributeValue orderAttr = map.get("order");
        if (orderAttr != null && orderAttr.n() != null) {
            q.setOrder(Integer.valueOf(orderAttr.n()));
        }
        AttributeValue optionsAttr = map.get("options");
        if (optionsAttr != null && optionsAttr.hasL()) {
            q.setOptions(optionsAttr.l().stream().map(AttributeValue::s).toList());
        }

        return q;
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private void validateCreateRequest(ReviewTemplate request) {
        if (request.getTemplateName() == null || request.getTemplateName().isBlank()) {
            throw new IllegalArgumentException("templateName is required.");
        }
        if (request.getTemplateName().length() > 100) {
            throw new IllegalArgumentException("templateName must not exceed 100 characters.");
        }
        if (request.getReviewType() == null || !ALLOWED_REVIEW_TYPES.contains(request.getReviewType().value())) {
            throw new IllegalArgumentException(
                    "reviewType is required. Allowed: " + ALLOWED_REVIEW_TYPES);
        }
        if (request.getQuestions() == null || request.getQuestions().isEmpty()) {
            throw new IllegalArgumentException("At least one question is required.");
        }
        for (TemplateQuestion q : request.getQuestions()) {
            if (q.getText() == null || q.getText().isBlank()) {
                throw new IllegalArgumentException("Each question must have non-empty text.");
            }
            if (q.getType() == null || !ALLOWED_QUESTION_TYPES.contains(q.getType().value())) {
                throw new IllegalArgumentException(
                        "Invalid question type '" + q.getType() + "'. Allowed: " + ALLOWED_QUESTION_TYPES);
            }
        }
    }

    private List<TemplateQuestion> normalizeQuestions(List<TemplateQuestion> questions) {
        List<TemplateQuestion> result = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            TemplateQuestion q = questions.get(i);
            if (q.getQuestionId() == null) {
                q.setQuestionId(UUID.randomUUID());
            }
            q.setOrder(i + 1);
            if (q.getRequired() == null) {
                q.setRequired(false);
            }
            if (q.getOptions() == null) {
                q.setOptions(List.of());
            }
            result.add(q);
        }
        return result;
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String now() {
        return Instant.now().toString();
    }

    private String attrStr(Map<String, AttributeValue> item, String key) {
        AttributeValue val = item.get(key);
        return val != null ? val.s() : null;
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}

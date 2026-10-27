package com.catalystflow.review.service;

import com.catalystflow.review.model.ReviewTemplate;
import com.catalystflow.review.model.TemplateQuestion;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class ReviewTemplateServiceTest {

    @Inject
    ReviewTemplateService service;

    @InjectMock
    DynamoDbClient mockDynamoDb;

    // ── Helpers ──────────────────────────────────────────────────────────────

    private TemplateQuestion question(String text, TemplateQuestion.TypeEnum type) {
        TemplateQuestion q = new TemplateQuestion();
        q.setText(text);
        q.setType(type);
        return q;
    }

    private ReviewTemplate validRequest() {
        ReviewTemplate t = new ReviewTemplate();
        t.setTemplateName("Quarterly Self Review");
        t.setReviewType(ReviewTemplate.ReviewTypeEnum.SELF);
        t.setDescription("Self assessment");
        t.setQuestions(List.of(question("What did you accomplish?", TemplateQuestion.TypeEnum.TEXT)));
        return t;
    }

    private Map<String, AttributeValue> dynamoItem(UUID id, String name, String type) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("templateId",   AttributeValue.builder().s(id.toString()).build());
        item.put("templateName", AttributeValue.builder().s(name).build());
        item.put("reviewType",   AttributeValue.builder().s(type).build());
        item.put("createdAt",    AttributeValue.builder().s("2026-01-01T00:00:00Z").build());
        item.put("updatedAt",    AttributeValue.builder().s("2026-01-01T00:00:00Z").build());
        item.put("usageCount",   AttributeValue.builder().n("0").build());
        return item;
    }

    private Map<String, AttributeValue> dynamoItemWithQuestions(UUID id, String name, String type) {
        Map<String, AttributeValue> item = dynamoItem(id, name, type);

        Map<String, AttributeValue> qMap = new HashMap<>();
        qMap.put("questionId",    AttributeValue.builder().s(UUID.randomUUID().toString()).build());
        qMap.put("text",          AttributeValue.builder().s("Round-trip question").build());
        qMap.put("type",          AttributeValue.builder().s("RATING").build());
        qMap.put("required",      AttributeValue.builder().bool(true).build());
        qMap.put("order",         AttributeValue.builder().n("1").build());
        qMap.put("idealResponse", AttributeValue.builder().s("Be specific").build());
        qMap.put("options",       AttributeValue.builder().l(
                AttributeValue.builder().s("low").build(),
                AttributeValue.builder().s("high").build()).build());

        item.put("questions", AttributeValue.builder()
                .l(AttributeValue.builder().m(qMap).build()).build());
        item.put("description", AttributeValue.builder().s("desc").build());
        item.put("lastUsedAt",  AttributeValue.builder().s("2026-04-01T00:00:00Z").build());
        return item;
    }

    /** Stub isNameTaken's underlying GSI query to return no rows. */
    private void stubNameNotTaken() {
        when(mockDynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().items(List.of()).build());
    }

    // ── getTemplate ──────────────────────────────────────────────────────────

    @Test
    void getTemplate_found_returnsTemplate() {
        UUID id = UUID.randomUUID();
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(dynamoItem(id, "T", "SELF")).build());

        Optional<ReviewTemplate> result = service.getTemplate(id.toString());

        assertTrue(result.isPresent());
        assertEquals(id, result.get().getTemplateId());
        assertEquals("T", result.get().getTemplateName());
        assertEquals(ReviewTemplate.ReviewTypeEnum.SELF, result.get().getReviewType());
    }

    @Test
    void getTemplate_notFound_returnsEmpty() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        assertTrue(service.getTemplate("missing").isEmpty());
    }

    @Test
    void getTemplate_roundTripsAllQuestionFields() {
        UUID id = UUID.randomUUID();
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(dynamoItemWithQuestions(id, "Round", "MANAGER"))
                        .build());

        ReviewTemplate result = service.getTemplate(id.toString()).orElseThrow();

        assertEquals("desc", result.getDescription());
        assertEquals("2026-04-01T00:00:00Z", result.getLastUsedAt());
        assertEquals(1, result.getQuestions().size());

        TemplateQuestion q = result.getQuestions().get(0);
        assertEquals("Round-trip question", q.getText());
        assertEquals(TemplateQuestion.TypeEnum.RATING, q.getType());
        assertEquals(true, q.getRequired());
        assertEquals(1, q.getOrder());
        assertEquals("Be specific", q.getIdealResponse());
        assertEquals(List.of("low", "high"), q.getOptions());
    }

    // ── getAllTemplates ──────────────────────────────────────────────────────

    @Test
    void getAllTemplates_noFilter_scansAndPaginates() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        ScanResponse page1 = ScanResponse.builder()
                .items(dynamoItem(id1, "T1", "SELF"))
                .lastEvaluatedKey(Map.of("templateId", AttributeValue.builder().s(id1.toString()).build()))
                .build();
        ScanResponse page2 = ScanResponse.builder()
                .items(dynamoItem(id2, "T2", "MANAGER"))
                .build();

        when(mockDynamoDb.scan(any(ScanRequest.class))).thenReturn(page1).thenReturn(page2);

        List<ReviewTemplate> result = service.getAllTemplates(null);

        assertEquals(2, result.size());
        verify(mockDynamoDb, times(2)).scan(any(ScanRequest.class));
    }

    @Test
    void getAllTemplates_blankFilter_treatedAsNoFilter() {
        when(mockDynamoDb.scan(any(ScanRequest.class)))
                .thenReturn(ScanResponse.builder().items(List.of()).build());

        List<ReviewTemplate> result = service.getAllTemplates("");

        assertTrue(result.isEmpty());
        verify(mockDynamoDb, never()).query(any(QueryRequest.class));
    }

    @Test
    void getAllTemplates_withFilter_queriesIndexAndPaginates() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        QueryResponse page1 = QueryResponse.builder()
                .items(dynamoItem(id1, "T1", "PEER_360"))
                .lastEvaluatedKey(Map.of("templateId", AttributeValue.builder().s(id1.toString()).build()))
                .build();
        QueryResponse page2 = QueryResponse.builder()
                .items(dynamoItem(id2, "T2", "PEER_360"))
                .build();

        when(mockDynamoDb.query(any(QueryRequest.class))).thenReturn(page1).thenReturn(page2);

        List<ReviewTemplate> result = service.getAllTemplates("PEER_360");

        assertEquals(2, result.size());
        verify(mockDynamoDb, times(2)).query(any(QueryRequest.class));
        verify(mockDynamoDb, never()).scan(any(ScanRequest.class));
    }

    @Test
    void getAllTemplates_invalidReviewType_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getAllTemplates("INVALID_TYPE"));
    }

    // ── createTemplate validation ────────────────────────────────────────────

    @Test
    void createTemplate_nullName_throws() {
        ReviewTemplate req = validRequest();
        req.setTemplateName(null);
        assertThrows(IllegalArgumentException.class, () -> service.createTemplate(req));
    }

    @Test
    void createTemplate_blankName_throws() {
        ReviewTemplate req = validRequest();
        req.setTemplateName("   ");
        assertThrows(IllegalArgumentException.class, () -> service.createTemplate(req));
    }

    @Test
    void createTemplate_nameTooLong_throws() {
        ReviewTemplate req = validRequest();
        req.setTemplateName("x".repeat(101));
        assertThrows(IllegalArgumentException.class, () -> service.createTemplate(req));
    }

    @Test
    void createTemplate_nullReviewType_throws() {
        ReviewTemplate req = validRequest();
        req.setReviewType(null);
        assertThrows(IllegalArgumentException.class, () -> service.createTemplate(req));
    }

    @Test
    void createTemplate_noQuestions_throws() {
        ReviewTemplate req = validRequest();
        req.setQuestions(List.of());
        assertThrows(IllegalArgumentException.class, () -> service.createTemplate(req));
    }

    @Test
    void createTemplate_questionWithBlankText_throws() {
        ReviewTemplate req = validRequest();
        req.setQuestions(List.of(question("  ", TemplateQuestion.TypeEnum.TEXT)));
        assertThrows(IllegalArgumentException.class, () -> service.createTemplate(req));
    }

    @Test
    void createTemplate_questionWithNullType_throws() {
        TemplateQuestion q = new TemplateQuestion();
        q.setText("Hi");
        ReviewTemplate req = validRequest();
        req.setQuestions(List.of(q));
        assertThrows(IllegalArgumentException.class, () -> service.createTemplate(req));
    }

    @Test
    void createTemplate_duplicateName_throws() {
        UUID existingId = UUID.randomUUID();
        when(mockDynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(dynamoItem(existingId, "Quarterly Self Review", "SELF"))
                        .build());

        assertThrows(IllegalArgumentException.class,
                () -> service.createTemplate(validRequest()));
        verify(mockDynamoDb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void createTemplate_happyPath_persistsAndAssignsIds() {
        stubNameNotTaken();
        when(mockDynamoDb.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());

        ReviewTemplate req = validRequest();
        ReviewTemplate created = service.createTemplate(req);

        assertNotNull(created.getTemplateId());
        assertNotNull(created.getCreatedAt());
        assertNotNull(created.getUpdatedAt());
        assertEquals(0, created.getUsageCount());
        // normalizeQuestions assigns an id and an order to each question
        assertNotNull(created.getQuestions().get(0).getQuestionId());
        assertEquals(1, created.getQuestions().get(0).getOrder());
        assertEquals(false, created.getQuestions().get(0).getRequired());
        assertEquals(List.of(), created.getQuestions().get(0).getOptions());

        verify(mockDynamoDb).putItem(argThat((PutItemRequest req2) ->
                req2.item().get("templateName").s().equals("Quarterly Self Review") &&
                req2.item().get("reviewType").s().equals("SELF")));
    }

    @Test
    void createTemplate_trimsLeadingTrailingWhitespace() {
        stubNameNotTaken();
        when(mockDynamoDb.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());

        ReviewTemplate req = validRequest();
        req.setTemplateName("  Padded  ");

        ReviewTemplate created = service.createTemplate(req);

        assertEquals("Padded", created.getTemplateName());
    }

    // ── updateTemplate ───────────────────────────────────────────────────────

    @Test
    void updateTemplate_notFound_returnsEmpty() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        Optional<ReviewTemplate> result = service.updateTemplate("missing", validRequest());

        assertTrue(result.isEmpty());
        verify(mockDynamoDb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void updateTemplate_partialFields_keepsExistingWhenNull() {
        UUID id = UUID.randomUUID();
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(dynamoItem(id, "Original", "MANAGER")).build());
        when(mockDynamoDb.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());

        ReviewTemplate patch = new ReviewTemplate();
        patch.setDescription("Just updating description");

        ReviewTemplate updated = service.updateTemplate(id.toString(), patch).orElseThrow();

        assertEquals("Original", updated.getTemplateName());
        assertEquals(ReviewTemplate.ReviewTypeEnum.MANAGER, updated.getReviewType());
        assertEquals("Just updating description", updated.getDescription());
    }

    @Test
    void updateTemplate_renameToDuplicate_throws() {
        UUID id = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(dynamoItem(id, "Original", "SELF")).build());
        // isNameTaken query returns ANOTHER template that holds the new name
        when(mockDynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(dynamoItem(otherId, "Renamed", "SELF"))
                        .build());

        ReviewTemplate patch = new ReviewTemplate();
        patch.setTemplateName("Renamed");

        assertThrows(IllegalArgumentException.class,
                () -> service.updateTemplate(id.toString(), patch));
        verify(mockDynamoDb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void updateTemplate_renameWhenOnlyTakenBySelf_succeeds() {
        UUID id = UUID.randomUUID();
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(dynamoItem(id, "Original", "SELF")).build());
        // Name only "taken" by the template itself — should be allowed
        when(mockDynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(dynamoItem(id, "Renamed", "SELF"))
                        .build());
        when(mockDynamoDb.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());

        ReviewTemplate patch = new ReviewTemplate();
        patch.setTemplateName("Renamed");

        ReviewTemplate updated = service.updateTemplate(id.toString(), patch).orElseThrow();
        assertEquals("Renamed", updated.getTemplateName());
    }

    @Test
    void updateTemplate_caseInsensitiveSameName_skipsConflictCheck() {
        UUID id = UUID.randomUUID();
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(dynamoItem(id, "Original", "SELF")).build());
        when(mockDynamoDb.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());

        ReviewTemplate patch = new ReviewTemplate();
        patch.setTemplateName("ORIGINAL"); // same name, different case

        ReviewTemplate updated = service.updateTemplate(id.toString(), patch).orElseThrow();
        assertEquals("ORIGINAL", updated.getTemplateName());
        // no query needed because the name didn't actually change
        verify(mockDynamoDb, never()).query(any(QueryRequest.class));
    }

    @Test
    void updateTemplate_replacesQuestionsWhenProvided() {
        UUID id = UUID.randomUUID();
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(dynamoItem(id, "Original", "SELF")).build());
        when(mockDynamoDb.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());

        ReviewTemplate patch = new ReviewTemplate();
        patch.setQuestions(List.of(
                question("New q1", TemplateQuestion.TypeEnum.TEXT),
                question("New q2", TemplateQuestion.TypeEnum.RATING)));

        ReviewTemplate updated = service.updateTemplate(id.toString(), patch).orElseThrow();
        assertEquals(2, updated.getQuestions().size());
        assertEquals(1, updated.getQuestions().get(0).getOrder());
        assertEquals(2, updated.getQuestions().get(1).getOrder());
    }

    @Test
    void updateTemplate_emptyQuestionsList_keepsExisting() {
        UUID id = UUID.randomUUID();
        Map<String, AttributeValue> existing = dynamoItemWithQuestions(id, "Original", "SELF");
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(existing).build());
        when(mockDynamoDb.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());

        ReviewTemplate patch = new ReviewTemplate();
        patch.setQuestions(List.of());

        ReviewTemplate updated = service.updateTemplate(id.toString(), patch).orElseThrow();
        // existing questions should be retained because update is treated as a no-op for empty list
        assertEquals(1, updated.getQuestions().size());
    }

    // ── deleteTemplate ───────────────────────────────────────────────────────

    @Test
    void deleteTemplate_notFound_returnsFalse() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        assertFalse(service.deleteTemplate("missing"));
        verify(mockDynamoDb, never()).deleteItem(any(DeleteItemRequest.class));
    }

    @Test
    void deleteTemplate_found_deletesAndReturnsTrue() {
        UUID id = UUID.randomUUID();
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(dynamoItem(id, "Doomed", "SELF")).build());
        when(mockDynamoDb.deleteItem(any(DeleteItemRequest.class)))
                .thenReturn(DeleteItemResponse.builder().build());

        assertTrue(service.deleteTemplate(id.toString()));
        verify(mockDynamoDb).deleteItem(any(DeleteItemRequest.class));
    }

    // ── recordUsage ──────────────────────────────────────────────────────────

    @Test
    void recordUsage_incrementsCounter() {
        when(mockDynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(UpdateItemResponse.builder().build());

        service.recordUsage("template-id");

        verify(mockDynamoDb).updateItem(argThat((UpdateItemRequest req) ->
                req.updateExpression().contains("usageCount") &&
                req.updateExpression().contains("if_not_exists")));
    }

    // ── isNameTaken ──────────────────────────────────────────────────────────

    @Test
    void isNameTaken_nullOrBlank_returnsFalse() {
        assertFalse(service.isNameTaken(null, null));
        assertFalse(service.isNameTaken("", null));
        assertFalse(service.isNameTaken("   ", null));
        verify(mockDynamoDb, never()).query(any(QueryRequest.class));
    }

    @Test
    void isNameTaken_otherTemplateHoldsName_returnsTrue() {
        when(mockDynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(dynamoItem(UUID.randomUUID(), "Taken", "SELF"))
                        .build());

        assertTrue(service.isNameTaken("Taken", null));
    }

    @Test
    void isNameTaken_onlyHeldBySelf_returnsFalse() {
        UUID selfId = UUID.randomUUID();
        when(mockDynamoDb.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder()
                        .items(dynamoItem(selfId, "Mine", "SELF"))
                        .build());

        assertFalse(service.isNameTaken("Mine", selfId.toString()));
    }

    // ── saveTemplate edge cases via createTemplate ───────────────────────────

    @Test
    void createTemplate_omitsDescriptionWhenBlank() {
        stubNameNotTaken();
        when(mockDynamoDb.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());

        ReviewTemplate req = validRequest();
        req.setDescription("   ");

        service.createTemplate(req);

        verify(mockDynamoDb).putItem(argThat((PutItemRequest r) ->
                !r.item().containsKey("description")));
    }

    @Test
    void createTemplate_writesAllQuestionAttributes() {
        stubNameNotTaken();
        when(mockDynamoDb.putItem(any(PutItemRequest.class))).thenReturn(PutItemResponse.builder().build());

        TemplateQuestion q = question("Rate yourself", TemplateQuestion.TypeEnum.RATING);
        q.setRequired(true);
        q.setIdealResponse("Aim for 4+");
        q.setOptions(List.of("1", "2", "3", "4", "5"));

        ReviewTemplate req = validRequest();
        req.setQuestions(List.of(q));

        service.createTemplate(req);

        verify(mockDynamoDb).putItem(argThat((PutItemRequest r) ->
                r.item().containsKey("questions") &&
                r.item().get("questions").hasL() &&
                r.item().get("questions").l().get(0).m().get("idealResponse") != null));
    }
}

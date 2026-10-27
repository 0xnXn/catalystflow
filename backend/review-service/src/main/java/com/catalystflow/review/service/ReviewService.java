package com.catalystflow.review.service;

import com.catalystflow.review.domain.ManagerGateStatus;
import com.catalystflow.review.domain.ReviewStatus;
import com.catalystflow.review.model.CreateReviewRequest;
import com.catalystflow.review.model.Milestone;
import com.catalystflow.review.model.Review;
import com.catalystflow.review.model.ReviewAnswer;
import com.catalystflow.review.model.ReviewCycle;
import com.catalystflow.review.model.TemplateSnapshot;
import com.catalystflow.review.model.UpdateReviewRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ReviewService {

    private static final Logger LOG = Logger.getLogger(ReviewService.class);

    static final String GSI_SUBJECT = "SubjectIndex";
    static final String GSI_AUTHOR = "AuthorIndex";
    static final String GSI_CYCLE = "CycleIndex";

    @Inject
    DynamoDbClient dynamoDb;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ReviewCycleService cycleService;

    @ConfigProperty(name = "reviews.table-name")
    String tableName;

    public Optional<Review> getReviewById(String reviewId) {
        GetItemResponse res = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("reviewId", s(reviewId)))
                .build());
        return res.hasItem() && !res.item().isEmpty()
                ? Optional.of(toReview(res.item()))
                : Optional.empty();
    }

    public List<Review> getReviewsForSubject(String subjectUserId) {
        return queryGsi(GSI_SUBJECT, "subjectUserId", subjectUserId, null);
    }

    public List<Review> getReviewsForAuthor(String authorUserId) {
        return queryGsi(GSI_AUTHOR, "authorUserId", authorUserId, null);
    }

    public List<Review> getReviewsForCycle(String cycleId) {
        return queryGsi(GSI_CYCLE, "cycleId", cycleId, null);
    }

    /**
     * All PEER_360 review rows for a given (cycle, subject) pair.
     * Used by the 360 assignment flow and the manager gate.
     */
    public List<Review> getPeer360Assignments(String cycleId, String subjectUserId) {
        requireField(cycleId, "cycleId");
        requireField(subjectUserId, "subjectUserId");
        return queryGsi(GSI_SUBJECT, "subjectUserId", subjectUserId, cycleId + "#PEER_360");
    }

    public ManagerGateStatus getManagerGateStatus(String cycleId, String subjectUserId) {
        requireField(cycleId, "cycleId");
        requireField(subjectUserId, "subjectUserId");

        List<Review> selfReviews = queryGsi(GSI_SUBJECT, "subjectUserId", subjectUserId, cycleId + "#SELF");
        List<Review> peer360Reviews = queryGsi(GSI_SUBJECT, "subjectUserId", subjectUserId, cycleId + "#PEER_360");
        boolean peer360Required = cycleRequiresPeer360(cycleId);

        if (selfReviews.size() > 1) {
            LOG.warnf("Multiple SELF reviews found for cycle=%s subject=%s; using latest updatedAt",
                    cycleId, subjectUserId);
        }

        Review self = selfReviews.stream()
                .max(Comparator.comparing(r -> Optional.ofNullable(r.getUpdatedAt()).orElse("")))
                .orElse(null);

        String selfStatus = self == null || self.getStatus() == null ? null : self.getStatus().name();
        boolean selfDone = self != null
                && (self.getStatus() == Review.StatusEnum.COMPLETED || self.getStatus() == Review.StatusEnum.PUBLISHED);

        List<String> incompletePeers = peer360Reviews.stream()
                .filter(review -> review.getStatus() != Review.StatusEnum.COMPLETED
                        && review.getStatus() != Review.StatusEnum.PUBLISHED)
                .map(Review::getAuthorUserId)
                .toList();

        int totalAssigned = peer360Reviews.size();
        int completedCount = totalAssigned - incompletePeers.size();

        // Peer-360 satisfaction:
        //   - if cycle does NOT require 360 → OK (empty set is fine)
        //   - if cycle requires 360 and no rows exist yet → NOT OK (peer360_unassigned)
        //   - if cycle requires 360 and any row is incomplete → NOT OK (peer360_incomplete)
        boolean peer360Satisfied;
        if (!peer360Required) {
            peer360Satisfied = incompletePeers.isEmpty();
        } else if (totalAssigned == 0) {
            peer360Satisfied = false;
        } else {
            peer360Satisfied = incompletePeers.isEmpty();
        }

        List<ManagerGateStatus.GateReason> reasons = new ArrayList<>();
        if (self == null) {
            reasons.add(ManagerGateStatus.GateReason.selfMissing());
        } else if (!selfDone) {
            reasons.add(ManagerGateStatus.GateReason.selfIncomplete(selfStatus));
        }
        if (peer360Required && totalAssigned == 0) {
            reasons.add(ManagerGateStatus.GateReason.peer360Unassigned());
        } else if (!incompletePeers.isEmpty()) {
            reasons.add(ManagerGateStatus.GateReason.peer360Incomplete(incompletePeers.size()));
        }

        return new ManagerGateStatus(
                selfDone && peer360Satisfied,
                cycleId,
                subjectUserId,
                new ManagerGateStatus.SelfReviewSummary(selfStatus, self == null ? null : self.getReviewId()),
                new ManagerGateStatus.Peer360Summary(peer360Required, totalAssigned, completedCount, incompletePeers),
                reasons
        );
    }

    /**
     * True if the cycle has at least one milestone whose templateSnapshot.reviewType is PEER_360.
     * Used by the manager gate to decide whether peer reviews are required for this cycle.
     * Defaults to false if the cycle is missing or has no milestones (fail-open).
     */
    private boolean cycleRequiresPeer360(String cycleId) {
        ReviewCycle cycle;
        try {
            cycle = cycleService.getReviewCycle(cycleId).orElse(null);
        } catch (Exception e) {
            LOG.warnf(e, "Could not load cycle %s while computing manager gate; defaulting to peer360Required=false",
                    cycleId);
            return false;
        }
        if (cycle == null || cycle.getMilestones() == null) return false;
        for (Milestone m : cycle.getMilestones()) {
            TemplateSnapshot snap = m.getTemplateSnapshot();
            if (snap != null
                    && snap.getReviewType() == TemplateSnapshot.ReviewTypeEnum.PEER_360) {
                return true;
            }
        }
        return false;
    }

    public Review create(CreateReviewRequest req, String createdBy) {
        if (req == null) throw new BadRequestException("Request body is required");
        requireField(req.getCycleId(), "cycleId");
        requireField(req.getTemplateId(), "templateId");
        requireField(req.getSubjectUserId(), "subjectUserId");
        requireField(req.getAuthorUserId(), "authorUserId");
        if (req.getReviewType() == null) throw new BadRequestException("'reviewType' is required");

        // One MANAGER review per (cycle, subject). The admin "Start Review" override and a
        // reassigned manager both POST a fresh manager review (the form omits reviewId),
        // which would silently create a duplicate — the backstop the frontend lacks.
        // (PEER_360 legitimately has many authors; SELF is handled by its own form flow.)
        if (req.getReviewType() == CreateReviewRequest.ReviewTypeEnum.MANAGER) {
            Review existingManager = getReviewsForSubject(req.getSubjectUserId()).stream()
                    .filter(r -> req.getCycleId().equals(r.getCycleId())
                            && r.getReviewType() == Review.ReviewTypeEnum.MANAGER)
                    .findFirst()
                    .orElse(null);
            if (existingManager != null) {
                throw new WebApplicationException(String.format(
                        "A MANAGER review already exists for subjectUserId=%s in cycleId=%s (reviewId=%s). "
                                + "Edit the existing review instead of creating a new one.",
                        req.getSubjectUserId(), req.getCycleId(), existingManager.getReviewId()),
                        Response.Status.CONFLICT);
            }
        }

        String now = Instant.now().toString();
        Review review = new Review();
        review.setReviewId(UUID.randomUUID().toString());
        review.setCycleId(req.getCycleId());
        review.setTemplateId(req.getTemplateId());
        review.setSubjectUserId(req.getSubjectUserId());
        review.setAuthorUserId(req.getAuthorUserId());
        review.setReviewType(toReviewType(req.getReviewType()));
        review.setStatus(Review.StatusEnum.NOT_STARTED);
        review.setAnswers(new ArrayList<>());
        review.setCreatedAt(now);
        review.setUpdatedAt(now);
        review.setCreatedBy(createdBy);
        review.setVersion(1);

        putItem(review);
        LOG.infof("Created review %s (%s) subject=%s cycle=%s",
                review.getReviewId(), review.getReviewType(), review.getSubjectUserId(), review.getCycleId());
        return review;
    }

    public Review update(String reviewId, UpdateReviewRequest req) {
        if (req == null) throw new BadRequestException("Request body is required");

        Review existing = getReviewById(reviewId)
                .orElseThrow(() -> new NotFoundException("Review not found: " + reviewId));

        if (req.getVersion() != null && !req.getVersion().equals(existing.getVersion())) {
            throw new WebApplicationException(
                    "Stale review - reload and try again.", Response.Status.CONFLICT);
        }

        Review.StatusEnum target = req.getStatus() != null
                ? toReviewStatus(req.getStatus())
                : existing.getStatus();
        if (!ReviewStatus.canTransition(existing.getReviewType(), existing.getStatus(), target)) {
            throw new BadRequestException(String.format(
                    "Illegal status transition for %s: %s -> %s",
                    existing.getReviewType(), existing.getStatus(), target));
        }

        // The generated UpdateReviewRequest defaults answers to new ArrayList<>() (not null),
        // so a partial update (e.g. persisting only insightsJobId) arrives with answers=[]
        // and the null-only guard would silently wipe all saved answers. Skip the field
        // when the list is empty — no legitimate caller intentionally clears every answer.
        if (req.getAnswers() != null && !req.getAnswers().isEmpty()) existing.setAnswers(req.getAnswers());
        if (req.getOverallRating() != null) existing.setOverallRating(req.getOverallRating());
        if (req.getOverallComment() != null) existing.setOverallComment(req.getOverallComment());
        if (req.getInsightsJobId() != null) existing.setInsightsJobId(req.getInsightsJobId());
        if (req.getAnalyzeJobId() != null) existing.setAnalyzeJobId(req.getAnalyzeJobId());
        if (req.getPeer360DigestJobId() != null) existing.setPeer360DigestJobId(req.getPeer360DigestJobId());
        if (req.getManagerGoldenJobId() != null) existing.setManagerGoldenJobId(req.getManagerGoldenJobId());

        String now = Instant.now().toString();
        if (target == Review.StatusEnum.COMPLETED && existing.getStatus() != Review.StatusEnum.COMPLETED) {
            existing.setSubmittedAt(now);
        }
        if (target == Review.StatusEnum.PUBLISHED && existing.getStatus() != Review.StatusEnum.PUBLISHED) {
            existing.setPublishedAt(now);
        }

        existing.setStatus(target);
        existing.setUpdatedAt(now);
        Integer expectedVersion = existing.getVersion();
        existing.setVersion((existing.getVersion() == null ? 1 : existing.getVersion()) + 1);

        try {
            putItem(existing, expectedVersion);
        } catch (ConditionalCheckFailedException e) {
            throw new WebApplicationException(
                    "Stale review - reload and try again.", Response.Status.CONFLICT);
        }
        LOG.infof("Updated review %s status=%s version=%d",
                existing.getReviewId(), existing.getStatus(), existing.getVersion());
        return existing;
    }

    public List<Review> publishBundle(String cycleId, String subjectUserId, String publishedBy) {
        List<Review> rows = getReviewsForSubject(subjectUserId).stream()
                .filter(r -> cycleId.equals(r.getCycleId()))
                .toList();

        if (rows.isEmpty()) {
            throw new NotFoundException(
                    "No reviews found for subjectUserId=" + subjectUserId + " in cycleId=" + cycleId);
        }

        for (Review r : rows) {
            if (r.getStatus() == Review.StatusEnum.PUBLISHED) continue;
            if (r.getStatus() != Review.StatusEnum.COMPLETED) {
                throw new BadRequestException(String.format(
                        "Cannot publish bundle - %s review is %s (must be COMPLETED). reviewId=%s",
                        r.getReviewType(), r.getStatus(), r.getReviewId()));
            }
            // The MANAGER review is the feedback released to the employee; refuse to publish
            // an empty one (no answers), which would surface as a blank "Completed" review on
            // the subject's dashboard. Empty self/peer reviews are the author's own doing and
            // do not gate the bundle.
            if (r.getReviewType() == Review.ReviewTypeEnum.MANAGER
                    && (r.getAnswers() == null || r.getAnswers().isEmpty())) {
                throw new BadRequestException(String.format(
                        "Cannot publish bundle - MANAGER review has no answers. reviewId=%s",
                        r.getReviewId()));
            }
        }

        List<Review> toWrite = rows.stream()
                .filter(r -> r.getStatus() != Review.StatusEnum.PUBLISHED)
                .toList();

        if (toWrite.isEmpty()) {
            return rows;
        }
        if (toWrite.size() > 100) {
            throw new BadRequestException(
                    "Bundle has " + toWrite.size() + " reviews; DDB TransactWriteItems cap is 100");
        }

        String now = Instant.now().toString();
        List<TransactWriteItem> ops = new ArrayList<>(toWrite.size());
        for (Review r : toWrite) {
            Integer expectedVersion = r.getVersion();
            r.setStatus(Review.StatusEnum.PUBLISHED);
            r.setPublishedAt(now);
            r.setPublishedBy(publishedBy);
            r.setUpdatedAt(now);
            r.setVersion((r.getVersion() == null ? 1 : r.getVersion()) + 1);

            Put.Builder putBuilder = Put.builder()
                    .tableName(tableName)
                    .item(toItem(r));
            if (expectedVersion != null) {
                putBuilder.conditionExpression("version = :expected")
                        .expressionAttributeValues(Map.of(
                                ":expected", AttributeValue.builder()
                                        .n(String.valueOf(expectedVersion))
                                        .build()));
            } else {
                putBuilder.conditionExpression("attribute_not_exists(version)");
            }
            ops.add(TransactWriteItem.builder().put(putBuilder.build()).build());
        }

        try {
            dynamoDb.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(ops)
                    .build());
        } catch (TransactionCanceledException e) {
            throw new WebApplicationException(
                    "Bundle publish failed - one or more reviews changed concurrently. Reload and retry.",
                    Response.Status.CONFLICT);
        }

        LOG.infof("Published bundle for subject=%s cycle=%s (%d rows) by %s",
                subjectUserId, cycleId, toWrite.size(), publishedBy);
        return rows;
    }

    public String deriveOverallStatus(List<Review> reviews) {
        if (reviews.isEmpty()) return Review.StatusEnum.NOT_STARTED.name();
        boolean allPublished = reviews.stream().allMatch(r -> r.getStatus() == Review.StatusEnum.PUBLISHED);
        boolean allCompleted = reviews.stream().allMatch(r ->
                r.getStatus() == Review.StatusEnum.COMPLETED || r.getStatus() == Review.StatusEnum.PUBLISHED);
        boolean anyStarted = reviews.stream().anyMatch(r ->
                r.getStatus() == Review.StatusEnum.DRAFT
                        || r.getStatus() == Review.StatusEnum.IN_PROGRESS
                        || r.getStatus() == Review.StatusEnum.COMPLETED);
        if (allPublished) return Review.StatusEnum.PUBLISHED.name();
        if (allCompleted) return Review.StatusEnum.COMPLETED.name();
        if (anyStarted) return Review.StatusEnum.IN_PROGRESS.name();
        return Review.StatusEnum.NOT_STARTED.name();
    }

    public void delete(String reviewId) {
        dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("reviewId", s(reviewId)))
                .build());
        LOG.infof("Deleted review %s", reviewId);
    }

    private List<Review> queryGsi(String index, String pkAttr, String pkValue, String skPrefix) {
        QueryRequest.Builder b = QueryRequest.builder()
                .tableName(tableName)
                .indexName(index);

        if (skPrefix != null) {
            b.keyConditionExpression("#pk = :pk AND begins_with(#sk, :sk)")
                    .expressionAttributeNames(Map.of("#pk", pkAttr, "#sk", "cycleTypeKey"))
                    .expressionAttributeValues(Map.of(":pk", s(pkValue), ":sk", s(skPrefix)));
        } else {
            b.keyConditionExpression("#pk = :pk")
                    .expressionAttributeNames(Map.of("#pk", pkAttr))
                    .expressionAttributeValues(Map.of(":pk", s(pkValue)));
        }

        List<Review> reviews = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            QueryRequest request = startKey == null ? b.build() : b.exclusiveStartKey(startKey).build();
            QueryResponse response = dynamoDb.query(request);
            response.items().stream().map(this::toReview).forEach(reviews::add);
            startKey = response.lastEvaluatedKey();
        } while (startKey != null && !startKey.isEmpty());
        return reviews;
    }

    private void putItem(Review r) {
        putItem(r, null);
    }

    private void putItem(Review r, Integer expectedVersion) {
        PutItemRequest.Builder builder = PutItemRequest.builder()
                .tableName(tableName)
                .item(toItem(r));
        if (expectedVersion != null) {
            builder.conditionExpression("version = :expected")
                    .expressionAttributeValues(Map.of(
                            ":expected", AttributeValue.builder()
                                    .n(String.valueOf(expectedVersion))
                                    .build()));
        }
        dynamoDb.putItem(builder.build());
    }

    private Map<String, AttributeValue> toItem(Review r) {
        Map<String, AttributeValue> item = new HashMap<>();

        put(item, "reviewId", r.getReviewId());
        put(item, "cycleId", r.getCycleId());
        put(item, "templateId", r.getTemplateId());
        put(item, "subjectUserId", r.getSubjectUserId());
        put(item, "authorUserId", r.getAuthorUserId());
        put(item, "overallRating", r.getOverallRating());
        put(item, "overallComment", r.getOverallComment());
        put(item, "insightsJobId", r.getInsightsJobId());
        put(item, "analyzeJobId", r.getAnalyzeJobId());
        put(item, "peer360DigestJobId", r.getPeer360DigestJobId());
        put(item, "managerGoldenJobId", r.getManagerGoldenJobId());
        put(item, "submittedAt", r.getSubmittedAt());
        put(item, "publishedAt", r.getPublishedAt());
        put(item, "publishedBy", r.getPublishedBy());
        put(item, "createdAt", r.getCreatedAt());
        put(item, "updatedAt", r.getUpdatedAt());
        put(item, "createdBy", r.getCreatedBy());

        if (r.getReviewType() != null) put(item, "reviewType", r.getReviewType().name());
        if (r.getStatus() != null) put(item, "status", r.getStatus().name());
        if (r.getVersion() != null) item.put("version", AttributeValue.builder().n(r.getVersion().toString()).build());

        if (r.getCycleId() != null && r.getReviewType() != null) {
            put(item, "cycleTypeKey", r.getCycleId() + "#" + r.getReviewType().name());
        }

        if (r.getAnswers() != null) {
            try {
                put(item, "answers", objectMapper.writeValueAsString(r.getAnswers()));
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialise answers for review " + r.getReviewId(), e);
            }
        }

        return item;
    }

    private Review toReview(Map<String, AttributeValue> item) {
        Review r = new Review();
        r.setReviewId(str(item, "reviewId"));
        r.setCycleId(str(item, "cycleId"));
        r.setTemplateId(str(item, "templateId"));
        r.setSubjectUserId(str(item, "subjectUserId"));
        r.setAuthorUserId(str(item, "authorUserId"));
        r.setOverallRating(str(item, "overallRating"));
        r.setOverallComment(str(item, "overallComment"));
        r.setInsightsJobId(str(item, "insightsJobId"));
        r.setAnalyzeJobId(str(item, "analyzeJobId"));
        r.setPeer360DigestJobId(str(item, "peer360DigestJobId"));
        r.setManagerGoldenJobId(str(item, "managerGoldenJobId"));
        r.setSubmittedAt(str(item, "submittedAt"));
        r.setPublishedAt(str(item, "publishedAt"));
        r.setPublishedBy(str(item, "publishedBy"));
        r.setCreatedAt(str(item, "createdAt"));
        r.setUpdatedAt(str(item, "updatedAt"));
        r.setCreatedBy(str(item, "createdBy"));

        String type = str(item, "reviewType");
        String status = str(item, "status");
        if (type != null) r.setReviewType(Review.ReviewTypeEnum.fromValue(type));
        if (status != null) r.setStatus(Review.StatusEnum.fromValue(status));

        AttributeValue ver = item.get("version");
        if (ver != null && ver.n() != null) r.setVersion(Integer.valueOf(ver.n()));

        String answersJson = str(item, "answers");
        if (answersJson != null && !answersJson.isBlank()) {
            try {
                r.setAnswers(objectMapper.readValue(answersJson, new TypeReference<List<ReviewAnswer>>() {}));
            } catch (Exception e) {
                LOG.warnf(e, "Failed to deserialise answers for review %s", r.getReviewId());
                r.setAnswers(new ArrayList<>());
            }
        }

        return r;
    }

    private static Review.ReviewTypeEnum toReviewType(CreateReviewRequest.ReviewTypeEnum reviewType) {
        return Review.ReviewTypeEnum.fromValue(reviewType.value());
    }

    private static Review.StatusEnum toReviewStatus(UpdateReviewRequest.StatusEnum status) {
        return Review.StatusEnum.fromValue(status.value());
    }

    private static AttributeValue s(String v) {
        return AttributeValue.builder().s(v).build();
    }

    private static void put(Map<String, AttributeValue> item, String key, String value) {
        if (value != null) item.put(key, s(value));
    }

    private static String str(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return v != null ? v.s() : null;
    }

    private static void requireField(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("'" + field + "' is required");
        }
    }
}

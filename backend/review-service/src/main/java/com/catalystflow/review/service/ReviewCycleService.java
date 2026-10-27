package com.catalystflow.review.service;

import com.catalystflow.review.model.Milestone;
import com.catalystflow.review.model.ReviewCycle;
import com.catalystflow.review.model.TemplateQuestion;
import com.catalystflow.review.model.TemplateSnapshot;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service layer for managing review cycles.
 *
 * Current behavior:
 * - A cycle is created as DRAFT
 * - A cycle can be published from DRAFT to SCHEDULED
 * - A cycle can be activated from SCHEDULED to ACTIVE
 * - COMPLETED cycles cannot be updated
 *
 * Milestone behavior:
 * - Milestones are stored as a list
 * - Milestones are independent and may run in parallel
 * - Sequential continuity is not enforced
 * - Overlap is allowed
 *
 * Date behavior:
 * - Input supports yyyy-MM-dd, dd/MM/yyyy, yyyy/MM/dd
 * - Stored/output date format is dd/MM/yyyy
 *
 */
@ApplicationScoped
public class ReviewCycleService {

    @Inject
    DynamoDbClient dynamoDb;

    @ConfigProperty(name = "review-cycles.table-name")
    String reviewCyclesTableName;

    private static final Logger LOG = Logger.getLogger(ReviewCycleService.class);

    /**
     * Standard stored/output date format for cycle and milestone dates.
     */
    private static final DateTimeFormatter AU_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Supported input date formats.
     */
    private static final List<DateTimeFormatter> SUPPORTED_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd")
    );

    /**
     * Allowed cycle statuses.
     */
    private static final Set<String> ALLOWED_CYCLE_STATUSES = Set.of(
            "DRAFT",
            "SCHEDULED",
            "ACTIVE",
            "COMPLETED"
    );

    /**
     * Allowed milestone statuses.
     */
    private static final Set<String> ALLOWED_MILESTONE_STATUSES = Set.of(
            "NOT_STARTED",
            "OPEN",
            "IN_PROGRESS",
            "COMPLETED"
    );

    /**
     * Fetch a single cycle by cycleId.
     *
     * @param cycleId the unique cycle identifier
     * @return the cycle if found, otherwise empty
     */
    public Optional<ReviewCycle> getReviewCycle(String cycleId) {
        LOG.infof("Getting review cycle: %s", cycleId);

        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(reviewCyclesTableName)
                .key(Map.of("cycleId", AttributeValue.builder().s(cycleId).build()))
                .build());

        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(toReviewCycle(response.item()));
    }

    /**
     * Fetch all cycles, optionally filtered by status.
     *
     * If status is provided, the method queries the GSI:
     * status-startDate-index
     *
     * If status is not provided, the method scans the full table.
     *
     * @param status optional cycle status filter
     * @return list of cycles
     */
    public List<ReviewCycle> getAllCycles(String status) {
        if (status != null && !status.isBlank()) {
            if (!ALLOWED_CYCLE_STATUSES.contains(status)) {
                throw new IllegalArgumentException(
                        "Invalid review cycle status. Allowed values: " + ALLOWED_CYCLE_STATUSES
                );
            }

            QueryRequest.Builder requestBuilder = QueryRequest.builder()
                    .tableName(reviewCyclesTableName)
                    .indexName("status-startDate-index")
                    .keyConditionExpression("#s = :statusVal")
                    .expressionAttributeNames(Map.of("#s", "status"))
                    .expressionAttributeValues(Map.of(
                            ":statusVal", AttributeValue.builder().s(status).build()
                    ));

            List<ReviewCycle> cycles = new ArrayList<>();
            Map<String, AttributeValue> startKey = null;
            do {
                QueryRequest request = startKey == null
                        ? requestBuilder.build()
                        : requestBuilder.exclusiveStartKey(startKey).build();
                QueryResponse response = dynamoDb.query(request);
                response.items().stream().map(this::toReviewCycle).forEach(cycles::add);
                startKey = response.lastEvaluatedKey();
            } while (startKey != null && !startKey.isEmpty());
            return cycles;
        }

        ScanRequest.Builder requestBuilder = ScanRequest.builder()
                .tableName(reviewCyclesTableName);

        List<ReviewCycle> cycles = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            ScanRequest request = startKey == null
                    ? requestBuilder.build()
                    : requestBuilder.exclusiveStartKey(startKey).build();
            ScanResponse response = dynamoDb.scan(request);
            response.items().stream().map(this::toReviewCycle).forEach(cycles::add);
            startKey = response.lastEvaluatedKey();
        } while (startKey != null && !startKey.isEmpty());
        return cycles;
    }

    /**
     * Create a new cycle as DRAFT.
     *
     * Draft rules:
     * - cycleTitle is optional
     * - templateId is optional
     * - milestones may be incomplete
     * - missing milestone dates default to cycle startDate
     * - milestones may run in parallel
     * - if cycle boundaries exist, milestone dates must stay within those boundaries
     *
     * ID rules:
     * - if cycleId is provided by the client, it is used
     * - otherwise a UUID is generated
     *
     * @param request incoming cycle payload
     * @return created cycle
     */
    public ReviewCycle createCycle(ReviewCycle request) {
        validateDraftRequest(request);

        String cycleId = (request.getCycleId() != null && !request.getCycleId().isBlank())
                ? request.getCycleId()
                : UUID.randomUUID().toString();

        String formattedStartDate = normalizeOptionalDate(request.getStartDate());
        String formattedDueDate = normalizeOptionalDate(request.getDueDate());

        ReviewCycle created = new ReviewCycle();
        created.setCycleId(cycleId);
        created.setCycleTitle(request.getCycleTitle());
        created.setTemplateId(request.getTemplateId());
        created.setStartDate(formattedStartDate);
        created.setDueDate(formattedDueDate);
        created.setStatus(ReviewCycle.StatusEnum.DRAFT);
        created.setMilestones(copyMilestones(request.getMilestones()));

        normalizeMilestones(created.getMilestones());
        applyDefaultMilestoneDates(created);
        validateMilestonesBasic(created.getMilestones());

        if (created.getStartDate() != null && created.getDueDate() != null) {
            validateMilestonesWithinCycleDates(
                    created.getMilestones(),
                    parseFlexibleDate(created.getStartDate()),
                    parseFlexibleDate(created.getDueDate())
            );
        }

        saveCycle(created);

        LOG.infof("Created draft review cycle: %s", cycleId);
        return created;
    }

    /**
     * Publish a cycle from DRAFT to SCHEDULED.
     *
     * Publish rules:
     * - cycleTitle is required
     * - startDate is required
     * - dueDate is required
     * - milestones are required
     * - each milestone must have name
     * - each milestone must have assignedRoles
     * - missing milestone dates default to cycle startDate
     * - milestones may overlap and run in parallel
     * - all milestones must stay within cycle boundaries
     *
     * Audit rules:
     * - updatedAt is refreshed on publish
     *
     * @param cycleId cycle identifier
     * @return updated cycle if found
     */
    public Optional<ReviewCycle> publishCycle(String cycleId) {
        Optional<ReviewCycle> existingOpt = getReviewCycle(cycleId);
        if (existingOpt.isEmpty()) {
            return Optional.empty();
        }

        ReviewCycle cycle = existingOpt.get();

        if (cycle.getStatus() != ReviewCycle.StatusEnum.DRAFT) {
            throw new IllegalArgumentException("Only DRAFT review cycles can be published");
        }

        cycle.setStartDate(normalizeOptionalDate(cycle.getStartDate()));
        cycle.setDueDate(normalizeOptionalDate(cycle.getDueDate()));
        normalizeMilestones(cycle.getMilestones());
        applyDefaultMilestoneDates(cycle);

        validatePublishableCycle(cycle);

        cycle.setStatus(ReviewCycle.StatusEnum.SCHEDULED);
        saveCycle(cycle);

        LOG.infof("Published review cycle to SCHEDULED: %s", cycleId);
        return Optional.of(cycle);
    }

    /**
     * Update an existing cycle.
     *
     * Update rules:
     * 1. Missing milestone dates default to cycle.startDate
     * 2. If a milestone goes outside the cycle range, the cycle expands automatically
     * 3. If cycle.startDate changes, milestones using the old cycle.startDate update too
     * 4. If cycle.dueDate changes, milestones using the old cycle.dueDate update too
     *
     * Additional rules:
     * - COMPLETED cycles cannot be updated
     * - milestones remain independent
     * - no sequential enforcement
     * - overlap is allowed
     *
     * Audit rules:
     * - createdAt remains unchanged
     * - updatedAt is refreshed on update
     *
     * @param cycleId cycle identifier
     * @param request partial update payload
     * @return updated cycle if found
     */
    public Optional<ReviewCycle> updateCycle(String cycleId, ReviewCycle request) {
        Optional<ReviewCycle> existingOpt = getReviewCycle(cycleId);
        if (existingOpt.isEmpty()) {
            return Optional.empty();
        }

        ReviewCycle existing = existingOpt.get();

        if (existing.getStatus() == ReviewCycle.StatusEnum.COMPLETED) {
            throw new IllegalStateException(
                    "Cannot update cycle with status: " + existing.getStatus() +
                            ". Only DRAFT, ACTIVE and SCHEDULED cycles can be edited."
            );
        }

        String oldCycleStart = normalizeOptionalDate(existing.getStartDate());
        String oldCycleDue = normalizeOptionalDate(existing.getDueDate());

        ReviewCycle merged = mergeCycle(existing, request);

        merged.setStartDate(normalizeOptionalDate(merged.getStartDate()));
        merged.setDueDate(normalizeOptionalDate(merged.getDueDate()));
        normalizeMilestones(merged.getMilestones());

        syncMilestonesWithChangedCycleBoundaries(
                merged.getMilestones(),
                oldCycleStart,
                oldCycleDue,
                merged.getStartDate(),
                merged.getDueDate()
        );

        applyDefaultMilestoneDates(merged);

        // Expand cycle boundaries if any milestone goes outside the current range.
        expandCycleRangeToFitMilestones(merged);

        validateCycleRequestForUpdate(merged);
        validateMilestonesBasic(merged.getMilestones());

        if (merged.getStartDate() != null && merged.getDueDate() != null) {
            validateMilestonesWithinCycleDates(
                    merged.getMilestones(),
                    parseFlexibleDate(merged.getStartDate()),
                    parseFlexibleDate(merged.getDueDate())
            );
        }

        saveCycle(merged);

        LOG.infof("Updated review cycle: %s", cycleId);
        return Optional.of(merged);
    }

    /**
     * Delete a cycle by cycleId.
     *
     * @param cycleId cycle identifier
     * @return true if the cycle existed and was deleted, false otherwise
     */
    public boolean deleteCycle(String cycleId) {
        Optional<ReviewCycle> existing = getReviewCycle(cycleId);
        if (existing.isEmpty()) {
            return false;
        }

        dynamoDb.deleteItem(DeleteItemRequest.builder()
                .tableName(reviewCyclesTableName)
                .key(Map.of("cycleId", AttributeValue.builder().s(cycleId).build()))
                .build());

        LOG.infof("Deleted review cycle: %s", cycleId);
        return true;
    }

    /**
     * Activate a cycle from SCHEDULED to ACTIVE.
     *
     * Audit rules:
     * - updatedAt is refreshed on activate
     *
     * @param cycleId cycle identifier
     * @return updated cycle if found
     */
    public Optional<ReviewCycle> activateCycle(String cycleId) {
        Optional<ReviewCycle> existingOpt = getReviewCycle(cycleId);
        if (existingOpt.isEmpty()) {
            return Optional.empty();
        }

        ReviewCycle cycle = existingOpt.get();

        if (cycle.getStatus() != ReviewCycle.StatusEnum.SCHEDULED) {
            throw new IllegalStateException(
                    "Only SCHEDULED review cycles can be activated, but current status is: " + cycle.getStatus()
            );
        }

        cycle.setStatus(ReviewCycle.StatusEnum.ACTIVE);
        saveCycle(cycle);

        LOG.infof("Activated review cycle: %s", cycleId);
        return Optional.of(cycle);
    }

    /**
     * Force-complete an ACTIVE cycle. Sets status to COMPLETED immediately, bypassing the
     * daily-sync wait. Used when an admin wants to end a cycle ahead of its due date
     * (e.g., re-org, mistaken cycle, accelerated schedule).
     *
     * <p>Does NOT publish in-flight reviews — they stay in their current state. Admin can
     * bundle-publish per-subject afterward if needed.
     *
     * <p>Returns empty if no cycle with that id; throws {@link IllegalStateException} if
     * the cycle is not ACTIVE.
     */
    public Optional<ReviewCycle> completeCycle(String cycleId) {
        Optional<ReviewCycle> existingOpt = getReviewCycle(cycleId);
        if (existingOpt.isEmpty()) {
            return Optional.empty();
        }

        ReviewCycle cycle = existingOpt.get();

        if (cycle.getStatus() != ReviewCycle.StatusEnum.ACTIVE) {
            throw new IllegalStateException(
                    "Only ACTIVE review cycles can be force-completed, but current status is: " + cycle.getStatus()
            );
        }

        cycle.setStatus(ReviewCycle.StatusEnum.COMPLETED);
        saveCycle(cycle);

        LOG.infof("Force-completed review cycle: %s", cycleId);
        return Optional.of(cycle);
    }

    /**
     * Synchronize all cycle and milestone statuses based on the provided date.
     * This is intended to be called by a daily maintenance task.
     *
     * Logic:
     * 1. Cycle Activation: SCHEDULED -> ACTIVE if today >= startDate.
     * 2. Milestone Management (for ACTIVE cycles):
     *    - NOT_STARTED -> OPEN if today >= milestone.startDate.
     *    - OPEN/IN_PROGRESS -> COMPLETED if today > milestone.endDate.
     * 3. Cycle Completion: ACTIVE -> COMPLETED if today > dueDate.
     *
     * @param today the date to use for comparison (usually LocalDate.now())
     */
    public void syncCycleStatuses(LocalDate today) {
        LOG.infof("Starting daily cycle maintenance sync for date: %s", today);

        List<ReviewCycle> scheduled = getAllCycles("SCHEDULED");
        List<ReviewCycle> active = getAllCycles("ACTIVE");

        List<ReviewCycle> candidates = new ArrayList<>();
        candidates.addAll(scheduled);
        candidates.addAll(active);

        int cyclesUpdated = 0;
        int milestonesUpdated = 0;

        for (ReviewCycle cycle : candidates) {
            boolean changed = false;

            // 1. Cycle Activation (SCHEDULED -> ACTIVE)
            if (cycle.getStatus() == ReviewCycle.StatusEnum.SCHEDULED) {
                LocalDate start = parseFlexibleDate(cycle.getStartDate());
                if (!today.isBefore(start)) {
                    cycle.setStatus(ReviewCycle.StatusEnum.ACTIVE);
                    changed = true;
                    LOG.infof("[Maintenance] Cycle %s: SCHEDULED -> ACTIVE", cycle.getCycleId());
                }
            }

            // 2. Milestone Transitions (only for ACTIVE cycles)
            if (cycle.getStatus() == ReviewCycle.StatusEnum.ACTIVE && cycle.getMilestones() != null) {
                for (Milestone m : cycle.getMilestones()) {
                    Milestone.StatusEnum oldMStatus = m.getStatus();

                    // NOT_STARTED -> OPEN
                    if (oldMStatus == Milestone.StatusEnum.NOT_STARTED) {
                        LocalDate mStart = parseFlexibleDate(m.getStartDate());
                        if (!today.isBefore(mStart)) {
                            m.setStatus(Milestone.StatusEnum.OPEN);
                        }
                    }

                    // OPEN/IN_PROGRESS -> COMPLETED
                    if (m.getStatus() == Milestone.StatusEnum.OPEN
                            || m.getStatus() == Milestone.StatusEnum.IN_PROGRESS) {
                        LocalDate mEnd = parseFlexibleDate(m.getEndDate());
                        if (today.isAfter(mEnd)) {
                            m.setStatus(Milestone.StatusEnum.COMPLETED);
                        }
                    }

                    if (m.getStatus() != null && !m.getStatus().equals(oldMStatus)) {
                        changed = true;
                        milestonesUpdated++;
                        LOG.infof("[Maintenance] Cycle %s, Milestone '%s': %s -> %s",
                                cycle.getCycleId(), m.getName(), oldMStatus, m.getStatus());
                    }
                }
            }

            // 3. Cycle Completion (ACTIVE -> COMPLETED)
            if (cycle.getStatus() == ReviewCycle.StatusEnum.ACTIVE) {
                LocalDate due = parseFlexibleDate(cycle.getDueDate());
                if (today.isAfter(due)) {
                    cycle.setStatus(ReviewCycle.StatusEnum.COMPLETED);
                    changed = true;
                    LOG.infof("[Maintenance] Cycle %s: ACTIVE -> COMPLETED", cycle.getCycleId());

                    // Force-complete any milestone left in OPEN/IN_PROGRESS so
                    // the UI doesn't render a COMPLETED cycle with non-COMPLETED
                    // milestones inside. Code-review N4 (2026-05-11).
                    if (cycle.getMilestones() != null) {
                        for (Milestone m : cycle.getMilestones()) {
                            if (m.getStatus() == Milestone.StatusEnum.OPEN
                                    || m.getStatus() == Milestone.StatusEnum.IN_PROGRESS) {
                                Milestone.StatusEnum prev = m.getStatus();
                                m.setStatus(Milestone.StatusEnum.COMPLETED);
                                milestonesUpdated++;
                                LOG.infof("[Maintenance] Cycle %s, Milestone '%s': %s -> COMPLETED (forced by cycle completion)",
                                        cycle.getCycleId(), m.getName(), prev);
                            }
                        }
                    }
                }
            }

            if (changed) {
                saveCycle(cycle);
                cyclesUpdated++;
            }
        }

        LOG.infof("Maintenance complete. Cycles updated: %d, Milestones updated: %d",
                cyclesUpdated, milestonesUpdated);
    }

    /**
     * Persist the full cycle object into DynamoDB.
     *
     * The method stores:
     * - core cycle attributes
     * - audit timestamps
     * - milestones as a DynamoDB list of maps
     *
     * @param cycle cycle to persist
     */
    private void saveCycle(ReviewCycle cycle) {
        Map<String, AttributeValue> item = new HashMap<>();

        item.put("cycleId", AttributeValue.builder().s(cycle.getCycleId()).build());
        item.put("status", AttributeValue.builder().s(cycle.getStatus().value()).build());

        if (cycle.getCycleTitle() != null && !cycle.getCycleTitle().isBlank()) {
            item.put("cycleTitle", AttributeValue.builder().s(cycle.getCycleTitle()).build());
        }

        if (cycle.getTemplateId() != null && !cycle.getTemplateId().isBlank()) {
            item.put("templateId", AttributeValue.builder().s(cycle.getTemplateId()).build());
        }

        if (cycle.getStartDate() != null && !cycle.getStartDate().isBlank()) {
            item.put("startDate", AttributeValue.builder().s(cycle.getStartDate()).build());
        }

        if (cycle.getDueDate() != null && !cycle.getDueDate().isBlank()) {
            item.put("dueDate", AttributeValue.builder().s(cycle.getDueDate()).build());
        }

        if (cycle.getMilestones() != null && !cycle.getMilestones().isEmpty()) {
            item.put("milestones", AttributeValue.builder().l(writeMilestonesList(cycle.getMilestones())).build());
        }

        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(reviewCyclesTableName)
                .item(item)
                .build());
    }

    /**
     * Merge an incoming update request into an existing cycle.
     *
     * Merge behavior:
     * - fields present in the request replace existing values
     * - fields absent in the request keep existing values
     * - milestones are replaced as a whole if provided
     *
     * @param existing existing cycle from storage
     * @param request incoming update payload
     * @return merged cycle
     */
    private ReviewCycle mergeCycle(ReviewCycle existing, ReviewCycle request) {
        ReviewCycle merged = new ReviewCycle();

        merged.setCycleId(existing.getCycleId());
        merged.setCycleTitle(
                request.getCycleTitle() != null ? request.getCycleTitle() : existing.getCycleTitle()
        );
        merged.setTemplateId(
                request.getTemplateId() != null ? request.getTemplateId() : existing.getTemplateId()
        );
        merged.setStartDate(
                request.getStartDate() != null ? request.getStartDate() : existing.getStartDate()
        );
        merged.setDueDate(
                request.getDueDate() != null ? request.getDueDate() : existing.getDueDate()
        );
        merged.setStatus(
                request.getStatus() != null ? request.getStatus() : existing.getStatus()
        );
        merged.setMilestones(
                hasMilestoneUpdate(request)
                        ? copyMilestones(request.getMilestones())
                        : copyMilestones(existing.getMilestones())
        );

        return merged;
    }

    private boolean hasMilestoneUpdate(ReviewCycle request) {
        return request.getMilestones() != null && !request.getMilestones().isEmpty();
    }

    /**
     * Deep-copy a milestone list to avoid accidental mutation of the original list.
     *
     * @param source source milestone list
     * @return copied milestone list
     */
    private List<Milestone> copyMilestones(List<Milestone> source) {
        if (source == null) {
            return null;
        }

        List<Milestone> copied = new ArrayList<>();
        for (Milestone m : source) {
            if (m == null) {
                copied.add(null);
                continue;
            }

            Milestone clone = new Milestone();
            clone.setMilestoneId(m.getMilestoneId());
            clone.setName(m.getName());
            clone.setAssignedRoles(
                    m.getAssignedRoles() != null ? new ArrayList<>(m.getAssignedRoles()) : null
            );
            clone.setStartDate(m.getStartDate());
            clone.setEndDate(m.getEndDate());
            clone.setStatus(m.getStatus());
            clone.setExtensionDays(m.getExtensionDays());
            clone.setTemplateSnapshot(m.getTemplateSnapshot());

            copied.add(clone);
        }

        return copied;
    }

    /**
     * Validate a draft creation request.
     *
     * Draft is intentionally flexible, but still enforces:
     * - request body must exist
     * - incoming status cannot be anything except DRAFT
     * - cycle date order must be valid if both dates exist
     * - milestone basic date and status validation
     *
     * @param request incoming cycle payload
     */
    private void validateDraftRequest(ReviewCycle request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }

        if (request.getStatus() != null && request.getStatus() != ReviewCycle.StatusEnum.DRAFT) {
            throw new IllegalArgumentException("Review cycle must remain DRAFT until published");
        }

        validateCycleDateOrderIfPresent(request.getStartDate(), request.getDueDate());

        if (request.getMilestones() != null) {
            validateMilestonesBasic(request.getMilestones());
        }
    }

    /**
     * Validate a general update request.
     *
     * Update validation enforces:
     * - request body must exist
     * - cycle date order must be valid if both dates exist
     * - cycle status must be valid if provided
     *
     * @param request incoming update payload
     */
    private void validateCycleRequestForUpdate(ReviewCycle request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }

        validateCycleDateOrderIfPresent(request.getStartDate(), request.getDueDate());

        if (request.getStatus() != null
                && !ALLOWED_CYCLE_STATUSES.contains(request.getStatus().value())) {
            throw new IllegalArgumentException(
                    "Invalid review cycle status. Allowed values: " + ALLOWED_CYCLE_STATUSES
            );
        }
    }

    /**
     * Validate cycle start/end order only when both values are present.
     *
     * @param startDate optional start date
     * @param dueDate optional due date
     */
    private void validateCycleDateOrderIfPresent(String startDate, String dueDate) {
        if (startDate != null && !startDate.isBlank()) {
            parseFlexibleDate(startDate);
        }

        if (dueDate != null && !dueDate.isBlank()) {
            parseFlexibleDate(dueDate);
        }

        if (startDate != null && !startDate.isBlank()
                && dueDate != null && !dueDate.isBlank()) {
            LocalDate start = parseFlexibleDate(startDate);
            LocalDate end = parseFlexibleDate(dueDate);

            if (start.isAfter(end)) {
                throw new IllegalArgumentException("startDate must be on or before dueDate");
            }
        }
    }

    /**
     * Validate a list of milestones using basic validation rules.
     *
     * @param milestones milestone list
     */
    private void validateMilestonesBasic(List<Milestone> milestones) {
        if (milestones == null) {
            return;
        }

        for (int i = 0; i < milestones.size(); i++) {
            Milestone milestone = milestones.get(i);
            validateMilestoneBasic(milestone, i);
        }
    }

    /**
     * Validate one milestone using basic rules.
     *
     * Basic validation checks:
     * - milestone must not be null
     * - startDate must parse if present
     * - endDate must parse if present
     * - if both exist, startDate must not be after endDate
     * - status must be valid if present
     *
     * @param milestone milestone to validate
     * @param index index used for error reporting
     */
    private void validateMilestoneBasic(Milestone milestone, int index) {
        if (milestone == null) {
            throw new IllegalArgumentException("milestone at index " + index + " is null");
        }

        if (milestone.getStartDate() != null && !milestone.getStartDate().isBlank()) {
            parseFlexibleDate(milestone.getStartDate());
        }

        if (milestone.getEndDate() != null && !milestone.getEndDate().isBlank()) {
            parseFlexibleDate(milestone.getEndDate());
        }

        if (milestone.getStartDate() != null && !milestone.getStartDate().isBlank()
                && milestone.getEndDate() != null && !milestone.getEndDate().isBlank()) {
            LocalDate start = parseFlexibleDate(milestone.getStartDate());
            LocalDate end = parseFlexibleDate(milestone.getEndDate());

            if (start.isAfter(end)) {
                throw new IllegalArgumentException(
                        "milestone at index " + index + " has startDate after endDate"
                );
            }
        }

        validateMilestoneStatus(milestone, index);
    }

    /**
     * Validate milestone status if it is provided.
     *
     * @param milestone milestone to validate
     * @param index index used for error reporting
     */
    private void validateMilestoneStatus(Milestone milestone, int index) {
        if (milestone.getStatus() != null
                && !ALLOWED_MILESTONE_STATUSES.contains(milestone.getStatus().value())) {
            throw new IllegalArgumentException(
                    "Invalid milestone status at index " + index +
                            ". Allowed values: " + ALLOWED_MILESTONE_STATUSES
            );
        }
    }

    /**
     * Validate all strict rules required before publishing a cycle.
     *
     * Publish requires:
     * - cycleTitle
     * - startDate
     * - dueDate
     * - at least one milestone
     * - each milestone must pass publish-level validation
     *
     * @param cycle cycle to validate
     */
    private void validatePublishableCycle(ReviewCycle cycle) {
        if (cycle == null) {
            throw new IllegalArgumentException("Review cycle is required");
        }

        if (cycle.getCycleTitle() == null || cycle.getCycleTitle().isBlank()) {
            throw new IllegalArgumentException("cycleTitle is required before publishing");
        }

        if (cycle.getStartDate() == null || cycle.getStartDate().isBlank()) {
            throw new IllegalArgumentException("startDate is required before publishing");
        }

        if (cycle.getDueDate() == null || cycle.getDueDate().isBlank()) {
            throw new IllegalArgumentException("dueDate is required before publishing");
        }

        LocalDate cycleStart = parseFlexibleDate(cycle.getStartDate());
        LocalDate cycleDue = parseFlexibleDate(cycle.getDueDate());

        if (cycleStart.isAfter(cycleDue)) {
            throw new IllegalArgumentException("startDate must be on or before dueDate");
        }

        if (cycle.getMilestones() == null || cycle.getMilestones().isEmpty()) {
            throw new IllegalArgumentException("At least one milestone is required before publishing");
        }

        validatePublishableMilestones(cycle.getMilestones(), cycleStart, cycleDue);
    }

    /**
     * Validate milestone rules required before publishing.
     *
     * Publish-level milestone validation requires:
     * - non-null milestone
     * - name
     * - assignedRoles
     * - startDate
     * - endDate
     * - valid date order
     * - milestone must stay inside cycle date boundaries
     *
     * Note:
     * - milestones may overlap
     * - milestones may run in parallel
     *
     * @param milestones milestone list
     * @param cycleStart cycle start date
     * @param cycleDue cycle due date
     */
    private void validatePublishableMilestones(List<Milestone> milestones, LocalDate cycleStart, LocalDate cycleDue) {
        for (int i = 0; i < milestones.size(); i++) {
            Milestone milestone = milestones.get(i);

            if (milestone == null) {
                throw new IllegalArgumentException("milestone at index " + i + " is null");
            }

            if (milestone.getName() == null || milestone.getName().isBlank()) {
                throw new IllegalArgumentException("milestone name is required at index " + i);
            }

            if (milestone.getAssignedRoles() == null || milestone.getAssignedRoles().isEmpty()) {
                throw new IllegalArgumentException("assignedRoles is required at index " + i);
            }

            if (milestone.getStartDate() == null || milestone.getStartDate().isBlank()) {
                throw new IllegalArgumentException("startDate is required for milestone at index " + i);
            }

            if (milestone.getEndDate() == null || milestone.getEndDate().isBlank()) {
                throw new IllegalArgumentException("endDate is required for milestone at index " + i);
            }

            validateMilestoneBasic(milestone, i);

            LocalDate milestoneStart = parseFlexibleDate(milestone.getStartDate());
            LocalDate milestoneEnd = parseFlexibleDate(milestone.getEndDate());

            if (milestoneStart.isBefore(cycleStart)) {
                throw new IllegalArgumentException(
                        "milestone at index " + i + " cannot start before cycle startDate"
                );
            }

            if (milestoneEnd.isAfter(cycleDue)) {
                throw new IllegalArgumentException(
                        "milestone at index " + i + " cannot end after cycle dueDate"
                );
            }
        }
    }

    /**
     * Validate that all milestone dates stay within the cycle date boundaries.
     *
     * @param milestones milestone list
     * @param cycleStart cycle start date
     * @param cycleDue cycle due date
     */
    private void validateMilestonesWithinCycleDates(List<Milestone> milestones, LocalDate cycleStart, LocalDate cycleDue) {
        if (milestones == null) {
            return;
        }

        for (int i = 0; i < milestones.size(); i++) {
            Milestone milestone = milestones.get(i);
            if (milestone == null) {
                continue;
            }

            if (milestone.getStartDate() != null && !milestone.getStartDate().isBlank()) {
                LocalDate start = parseFlexibleDate(milestone.getStartDate());
                if (start.isBefore(cycleStart)) {
                    throw new IllegalArgumentException(
                            "milestone at index " + i + " startDate must be on or after cycle startDate"
                    );
                }
            }

            if (milestone.getEndDate() != null && !milestone.getEndDate().isBlank()) {
                LocalDate end = parseFlexibleDate(milestone.getEndDate());
                if (end.isAfter(cycleDue)) {
                    throw new IllegalArgumentException(
                            "milestone at index " + i + " endDate must be on or before cycle dueDate"
                    );
                }
            }
        }
    }

    /**
     * Normalize all milestones in a list.
     *
     * @param milestones milestone list
     */
    private void normalizeMilestones(List<Milestone> milestones) {
        if (milestones == null) {
            return;
        }

        for (Milestone milestone : milestones) {
            normalizeMilestone(milestone);
        }
    }

    /**
     * Normalize a single milestone.
     *
     * Normalization rules:
     * - auto-generate milestoneId if missing
     * - normalize startDate to dd/MM/yyyy if present
     * - normalize endDate to dd/MM/yyyy if present
     *
     * @param milestone milestone to normalize
     */
    private void normalizeMilestone(Milestone milestone) {
        if (milestone == null) {
            return;
        }

        if (milestone.getMilestoneId() == null || milestone.getMilestoneId().isBlank()) {
            milestone.setMilestoneId(UUID.randomUUID().toString());
        }

        if (milestone.getStartDate() != null && !milestone.getStartDate().isBlank()) {
            milestone.setStartDate(toAuFormat(parseFlexibleDate(milestone.getStartDate())));
        }

        if (milestone.getEndDate() != null && !milestone.getEndDate().isBlank()) {
            milestone.setEndDate(toAuFormat(parseFlexibleDate(milestone.getEndDate())));
        }
    }

    /**
     * Apply default milestone dates.
     *
     * Rule:
     * - If milestone.startDate is missing, default it to cycle.startDate
     * - If milestone.endDate is missing, default it to cycle.dueDate
     *
     * @param cycle cycle whose milestones should be defaulted
     */
    private void applyDefaultMilestoneDates(ReviewCycle cycle) {
        if (cycle == null || cycle.getMilestones() == null || cycle.getMilestones().isEmpty()) {
            return;
        }

        String cycleStart = cycle.getStartDate();
        String cycleDue = cycle.getDueDate();

        if (cycleStart == null || cycleStart.isBlank()) {
            return;
        }

        for (Milestone milestone : cycle.getMilestones()) {
            if (milestone == null) {
                continue;
            }

            if (milestone.getStartDate() == null || milestone.getStartDate().isBlank()) {
                milestone.setStartDate(cycleStart);
            }

            if (milestone.getEndDate() == null || milestone.getEndDate().isBlank()) {
                milestone.setEndDate(cycleDue != null && !cycleDue.isBlank() ? cycleDue : cycleStart);
            }
        }
    }

    /**
     * Sync milestone dates when cycle boundaries change.
     *
     * Rules:
     * - If cycle.startDate changes, only milestones currently using the old cycle.startDate are updated
     * - If cycle.dueDate changes, only milestones currently using the old cycle.dueDate are updated
     *
     * @param milestones milestone list
     * @param oldCycleStart old cycle start date
     * @param oldCycleDue old cycle due date
     * @param newCycleStart new cycle start date
     * @param newCycleDue new cycle due date
     */
    private void syncMilestonesWithChangedCycleBoundaries(
            List<Milestone> milestones,
            String oldCycleStart,
            String oldCycleDue,
            String newCycleStart,
            String newCycleDue
    ) {
        if (milestones == null || milestones.isEmpty()) {
            return;
        }

        boolean startChanged = oldCycleStart != null && newCycleStart != null && !oldCycleStart.equals(newCycleStart);
        boolean dueChanged = oldCycleDue != null && newCycleDue != null && !oldCycleDue.equals(newCycleDue);

        for (Milestone milestone : milestones) {
            if (milestone == null) {
                continue;
            }

            if (startChanged
                    && milestone.getStartDate() != null
                    && milestone.getStartDate().equals(oldCycleStart)) {
                milestone.setStartDate(newCycleStart);
            }

            if (dueChanged
                    && milestone.getEndDate() != null
                    && milestone.getEndDate().equals(oldCycleDue)) {
                milestone.setEndDate(newCycleDue);
            }
        }
    }

    /**
     * Expand cycle boundaries to fit all milestone dates.
     *
     * Rules:
     * - If any milestone starts earlier than the cycle startDate, cycle startDate moves earlier
     * - If any milestone ends later than the cycle dueDate, cycle dueDate moves later
     *
     * @param cycle cycle to expand
     */
    private void expandCycleRangeToFitMilestones(ReviewCycle cycle) {
        if (cycle == null || cycle.getMilestones() == null || cycle.getMilestones().isEmpty()) {
            return;
        }

        LocalDate minStart = cycle.getStartDate() != null && !cycle.getStartDate().isBlank()
                ? parseFlexibleDate(cycle.getStartDate())
                : null;
        LocalDate maxEnd = cycle.getDueDate() != null && !cycle.getDueDate().isBlank()
                ? parseFlexibleDate(cycle.getDueDate())
                : null;

        for (Milestone milestone : cycle.getMilestones()) {
            if (milestone == null) {
                continue;
            }

            if (milestone.getStartDate() != null && !milestone.getStartDate().isBlank()) {
                LocalDate milestoneStart = parseFlexibleDate(milestone.getStartDate());
                if (minStart == null || milestoneStart.isBefore(minStart)) {
                    minStart = milestoneStart;
                }
            }

            if (milestone.getEndDate() != null && !milestone.getEndDate().isBlank()) {
                LocalDate milestoneEnd = parseFlexibleDate(milestone.getEndDate());
                if (maxEnd == null || milestoneEnd.isAfter(maxEnd)) {
                    maxEnd = milestoneEnd;
                }
            }
        }

        if (minStart != null) {
            cycle.setStartDate(toAuFormat(minStart));
        }

        if (maxEnd != null) {
            cycle.setDueDate(toAuFormat(maxEnd));
        }
    }

    /**
     * Normalize an optional cycle date.
     *
     * @param value date string
     * @return normalized date in dd/MM/yyyy, or null if empty
     */
    private String normalizeOptionalDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return toAuFormat(parseFlexibleDate(value));
    }

    /**
     * Parse a date using the supported input formats.
     *
     * @param value date string
     * @return parsed LocalDate
     */
    private LocalDate parseFlexibleDate(String value) {
        for (DateTimeFormatter formatter : SUPPORTED_FORMATS) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }

        throw new IllegalArgumentException(
                "Invalid date format. Supported formats: yyyy-MM-dd, dd/MM/yyyy, yyyy/MM/dd"
        );
    }

    /**
     * Format a LocalDate to the standard stored/output format.
     *
     * @param date local date
     * @return formatted date string
     */
    private String toAuFormat(LocalDate date) {
        return date.format(AU_FORMAT);
    }

    /**
     * Safely read a string attribute from a DynamoDB item map.
     *
     * @param item DynamoDB item map
     * @param key attribute key
     * @return string value or null
     */
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

    /**
     * Convert a milestone list into a DynamoDB list of map attributes.
     *
     * @param milestones milestone list
     * @return DynamoDB list attributes
     */
    private List<AttributeValue> writeMilestonesList(List<Milestone> milestones) {
        List<AttributeValue> result = new ArrayList<>();

        for (Milestone milestone : milestones) {
            result.add(AttributeValue.builder().m(writeMilestoneMap(milestone)).build());
        }

        return result;
    }

    /**
     * Convert a single milestone into a DynamoDB map.
     *
     * @param milestone milestone object
     * @return DynamoDB map
     */
    private Map<String, AttributeValue> writeMilestoneMap(Milestone milestone) {
        Map<String, AttributeValue> result = new HashMap<>();

        if (milestone.getMilestoneId() != null && !milestone.getMilestoneId().isBlank()) {
            result.put("milestoneId", AttributeValue.builder().s(milestone.getMilestoneId()).build());
        }

        if (milestone.getName() != null && !milestone.getName().isBlank()) {
            result.put("name", AttributeValue.builder().s(milestone.getName()).build());
        }

        if (milestone.getAssignedRoles() != null && !milestone.getAssignedRoles().isEmpty()) {
            List<AttributeValue> roleValues = milestone.getAssignedRoles().stream()
                    .map(role -> AttributeValue.builder().s(role).build())
                    .toList();
            result.put("assignedRoles", AttributeValue.builder().l(roleValues).build());
        }

        if (milestone.getStartDate() != null && !milestone.getStartDate().isBlank()) {
            result.put("startDate", AttributeValue.builder().s(milestone.getStartDate()).build());
        }

        if (milestone.getEndDate() != null && !milestone.getEndDate().isBlank()) {
            result.put("endDate", AttributeValue.builder().s(milestone.getEndDate()).build());
        }

        if (milestone.getStatus() != null) {
            result.put("status", AttributeValue.builder().s(milestone.getStatus().value()).build());
        }

        if (milestone.getExtensionDays() != null) {
            result.put("extensionDays", AttributeValue.builder()
                    .n(String.valueOf(milestone.getExtensionDays()))
                    .build());
        }

        if (milestone.getTemplateSnapshot() != null) {
            result.put("templateSnapshot",
                    AttributeValue.builder().m(writeSnapshotMap(milestone.getTemplateSnapshot())).build());
        }

        return result;
    }

    private Map<String, AttributeValue> writeSnapshotMap(TemplateSnapshot snapshot) {
        Map<String, AttributeValue> result = new HashMap<>();

        if (snapshot.getTemplateId() != null && !snapshot.getTemplateId().isBlank()) {
            result.put("templateId", AttributeValue.builder().s(snapshot.getTemplateId()).build());
        }
        if (snapshot.getTemplateName() != null && !snapshot.getTemplateName().isBlank()) {
            result.put("templateName", AttributeValue.builder().s(snapshot.getTemplateName()).build());
        }
        if (snapshot.getReviewType() != null) {
            result.put("reviewType", AttributeValue.builder().s(snapshot.getReviewType().value()).build());
        }
        if (snapshot.getDescription() != null && !snapshot.getDescription().isBlank()) {
            result.put("description", AttributeValue.builder().s(snapshot.getDescription()).build());
        }
        if (snapshot.getSnapshotCreatedAt() != null && !snapshot.getSnapshotCreatedAt().isBlank()) {
            result.put("snapshotCreatedAt", AttributeValue.builder().s(snapshot.getSnapshotCreatedAt()).build());
        }
        if (snapshot.getQuestions() != null && !snapshot.getQuestions().isEmpty()) {
            result.put("questions", AttributeValue.builder()
                    .l(writeQuestionsList(snapshot.getQuestions())).build());
        }

        return result;
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

    /**
     * Convert a DynamoDB list of milestone maps back into milestone objects.
     *
     * @param values DynamoDB list attributes
     * @return milestone list
     */
    private List<Milestone> readMilestonesFromList(List<AttributeValue> values) {
        List<Milestone> milestones = new ArrayList<>();

        for (AttributeValue value : values) {
            if (value != null && value.hasM()) {
                milestones.add(readMilestoneFromMap(value.m()));
            }
        }

        return milestones;
    }

    /**
     * Convert a DynamoDB map into a milestone object.
     *
     * @param map DynamoDB map
     * @return milestone object
     */
    private Milestone readMilestoneFromMap(Map<String, AttributeValue> map) {
        Milestone milestone = new Milestone();

        milestone.setMilestoneId(attrStr(map, "milestoneId"));
        milestone.setName(attrStr(map, "name"));
        milestone.setStartDate(attrStr(map, "startDate"));
        milestone.setEndDate(attrStr(map, "endDate"));
        String status = attrStr(map, "status");
        if (status != null) {
            milestone.setStatus(Milestone.StatusEnum.fromValue(status));
        }

        AttributeValue assignedRolesAttr = map.get("assignedRoles");
        if (assignedRolesAttr != null && assignedRolesAttr.hasL()) {
            List<String> roles = assignedRolesAttr.l().stream()
                    .map(AttributeValue::s)
                    .toList();
            milestone.setAssignedRoles(roles);
        }

        AttributeValue extensionDaysAttr = map.get("extensionDays");
        if (extensionDaysAttr != null && extensionDaysAttr.n() != null) {
            milestone.setExtensionDays(Integer.valueOf(extensionDaysAttr.n()));
        }

        AttributeValue snapshotAttr = map.get("templateSnapshot");
        if (snapshotAttr != null && snapshotAttr.hasM()) {
            milestone.setTemplateSnapshot(readSnapshotFromMap(snapshotAttr.m()));
        }

        return milestone;
    }

    private TemplateSnapshot readSnapshotFromMap(Map<String, AttributeValue> map) {
        TemplateSnapshot snapshot = new TemplateSnapshot();
        snapshot.setTemplateId(attrStr(map, "templateId"));
        snapshot.setTemplateName(attrStr(map, "templateName"));
        String reviewType = attrStr(map, "reviewType");
        if (reviewType != null) {
            snapshot.setReviewType(TemplateSnapshot.ReviewTypeEnum.fromValue(reviewType));
        }
        snapshot.setDescription(attrStr(map, "description"));
        snapshot.setSnapshotCreatedAt(attrStr(map, "snapshotCreatedAt"));

        AttributeValue questionsAttr = map.get("questions");
        if (questionsAttr != null && questionsAttr.hasL()) {
            snapshot.setQuestions(readQuestionsFromList(questionsAttr.l()));
        }

        return snapshot;
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

    /**
     * Convert a DynamoDB item into a ReviewCycle object.
     *
     * @param item DynamoDB item
     * @return review cycle object
     */
    private ReviewCycle toReviewCycle(Map<String, AttributeValue> item) {
        ReviewCycle cycle = new ReviewCycle();
        cycle.setCycleId(attrStr(item, "cycleId"));
        cycle.setCycleTitle(attrStr(item, "cycleTitle"));
        cycle.setTemplateId(attrStr(item, "templateId"));
        cycle.setStartDate(attrStr(item, "startDate"));
        cycle.setDueDate(attrStr(item, "dueDate"));
        String status = attrStr(item, "status");
        if (status != null) {
            cycle.setStatus(ReviewCycle.StatusEnum.fromValue(status));
        }

        AttributeValue milestonesAttr = item.get("milestones");
        if (milestonesAttr != null && milestonesAttr.hasL()) {
            cycle.setMilestones(readMilestonesFromList(milestonesAttr.l()));
        }

        return cycle;
    }
}

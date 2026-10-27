package com.catalystflow.review.domain;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

// GraalVM native image strips reflective metadata for hand-written classes by default,
// so Jackson can't introspect record components at runtime. Without these annotations
// the response body fails to serialise and the Lambda returns an opaque 4xx instead
// of the gate payload — surfaced as the e2e ManagerGate 400s on 2026-05-18.
@RegisterForReflection
public record ManagerGateStatus(
        boolean open,
        String cycleId,
        String subjectUserId,
        SelfReviewSummary selfReview,
        Peer360Summary peer360,
        List<GateReason> reasons
) {
    @RegisterForReflection
    public record SelfReviewSummary(String status, String reviewId) {}

    /**
     * @param required true if the cycle's milestones include at least one PEER_360 template —
     *                 in which case the gate stays closed until ≥1 PEER_360 review row is COMPLETED
     *                 (and any others are also COMPLETED). false → empty PEER_360 set is acceptable.
     */
    @RegisterForReflection
    public record Peer360Summary(
            boolean required,
            int totalAssigned,
            int completedCount,
            List<String> incompleteAuthorUserIds
    ) {}

    @RegisterForReflection
    public record GateReason(String type, Integer missingCount, String currentStatus) {
        public static GateReason selfMissing() {
            return new GateReason("self_missing", null, null);
        }

        public static GateReason selfIncomplete(String status) {
            return new GateReason("self_incomplete", null, status);
        }

        public static GateReason peer360Incomplete(int missing) {
            return new GateReason("peer360_incomplete", missing, null);
        }

        /** Cycle has a PEER_360 milestone but the manager hasn't created any assignments yet. */
        public static GateReason peer360Unassigned() {
            return new GateReason("peer360_unassigned", null, null);
        }
    }
}

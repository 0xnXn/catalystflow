package com.catalystflow.ai.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Envelope for a {@code PEER_360_DIGEST} job. Unlike manager-side jobs which
 * key off a single reviewId, the digest spans every completed PEER_360 row
 * for a single (cycleId, subjectUserId) pair — the worker re-queries the
 * SubjectIndex GSI at run time so the snapshot is current.
 */
@RegisterForReflection
public class Peer360DigestJobRequest {
    public String taskType;
    public String cycleId;
    public String subjectUserId;
    public String requestedBy;

    public static Peer360DigestJobRequest of(String taskType, String cycleId,
                                             String subjectUserId, String requestedBy) {
        Peer360DigestJobRequest r = new Peer360DigestJobRequest();
        r.taskType = taskType;
        r.cycleId = cycleId;
        r.subjectUserId = subjectUserId;
        r.requestedBy = requestedBy;
        return r;
    }
}

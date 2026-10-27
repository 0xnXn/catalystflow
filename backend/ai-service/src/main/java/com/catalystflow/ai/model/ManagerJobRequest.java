package com.catalystflow.ai.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ManagerJobRequest {
    public String taskType;
    public String reviewId;
    public String requestedBy;
    /**
     * Optional per-question payload — set by MANAGER_QUESTION_SUGGEST jobs so
     * the worker can re-construct the Gemini prompt without another DDB roundtrip.
     */
    public QuestionSuggestRequest payload;

    public static ManagerJobRequest of(String taskType, String reviewId, String requestedBy) {
        ManagerJobRequest r = new ManagerJobRequest();
        r.taskType = taskType;
        r.reviewId = reviewId;
        r.requestedBy = requestedBy;
        return r;
    }
}

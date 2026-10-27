package com.catalystflow.ai.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class QuestionSuggestRequest {
    public String questionId;
    public String questionText;
    public String managerCurrentAnswer;
    public String discipline;
    public String seniority;
    public EmployeeReview employeeReview;
    public PeerDigest peerDigest;

    @RegisterForReflection
    public static class EmployeeReview {
        public java.util.List<EmployeeAnswer> answers;
        public String overallComment;
        public String overallRating;
    }

    @RegisterForReflection
    public static class EmployeeAnswer {
        public String questionId;
        public String questionText;
        public String response;
    }

    @RegisterForReflection
    public static class PeerDigest {
        public java.util.List<String> themes;
        public java.util.List<String> consensus;
        public java.util.List<String> dissent;
        public String overallSentiment;
    }
}

package com.catalystflow.ai.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class Answer {
    public String questionId;
    public String response;
}

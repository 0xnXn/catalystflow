package com.catalystflow.ai.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class TemplateQuestion {
    public String questionId;
    public String text;
    public String expectedResponse;
}

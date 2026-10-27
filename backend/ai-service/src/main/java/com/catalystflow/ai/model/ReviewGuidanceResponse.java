package com.catalystflow.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReviewGuidanceResponse {
    public List<String> missingAreas;
    public List<String> thinAreas;
    public List<String> wellCoveredAreas;
    public String overallGuidance;
}

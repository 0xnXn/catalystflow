package com.catalystflow.ai.service;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure unit tests for {@link GeminiTelemetryException}. Validates that the
 * carried telemetry fields survive construction and that the {@link
 * jakarta.ws.rs.WebApplicationException} status + message contract is preserved
 * so {@code classifyError} keeps working downstream.
 */
class GeminiTelemetryExceptionTest {

    @Test
    void preservesTokenCountsAndModel() {
        GeminiTelemetryException ex = new GeminiTelemetryException(
                "TRUNCATED: response cut off",
                Response.Status.BAD_GATEWAY,
                1024, 256, "gemini-2.5-flash");

        assertEquals(1024, ex.tokenIn);
        assertEquals(256, ex.tokenOut);
        assertEquals("gemini-2.5-flash", ex.modelUsed);
    }

    @Test
    void preservesResponseStatus() {
        GeminiTelemetryException ex = new GeminiTelemetryException(
                "BLOCKED: safety", Response.Status.FORBIDDEN, 10, 0, "gemini-2.5-flash");

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), ex.getResponse().getStatus());
    }

    @Test
    void preservesMessagePrefix() {
        GeminiTelemetryException ex = new GeminiTelemetryException(
                "MALFORMED: not JSON", Response.Status.BAD_REQUEST, 50, 5, "gemini-2.5-flash");

        assertEquals("MALFORMED: not JSON", ex.getMessage());
    }

    @Test
    void allowsNullTokenCounts() {
        GeminiTelemetryException ex = new GeminiTelemetryException(
                "BLOCKED: no usage", Response.Status.FORBIDDEN, null, null, null);

        assertNull(ex.tokenIn);
        assertNull(ex.tokenOut);
        assertNull(ex.modelUsed);
    }
}

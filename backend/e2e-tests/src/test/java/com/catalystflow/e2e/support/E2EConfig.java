package com.catalystflow.e2e.support;

/**
 * Reads E2E configuration from environment variables.
 * All required vars are validated at class-load time so tests fail fast
 * with a clear message rather than a cryptic NullPointerException.
 *
 * <p>The three review-domain table names are intentionally {@link #require}d
 * with no default. An earlier version fell back to prod-shaped names
 * ("CatalystFlow_Reviews", etc.) — when a buildspec forgot to pass the env var,
 * the API still wrote rows to the dev tables (because the deployed Lambda
 * picks the suffix from its own {@code Environment} parameter) but the
 * teardown deletes were aimed at the prod table names and silently failed.
 * That left 144 orphan cycles in {@code CatalystFlow_ReviewCycles_dev} across
 * 4 runs before we noticed (audit 2026-05-18). Fail-loud on missing env vars
 * makes any future buildspec drift impossible to ship.
 *
 * <p>{@code USERS_TABLE} stays {@link #optional} because legacy local-dev
 * scripts predate the SSM-published table name.
 */
public final class E2EConfig {

    public static final String API_URL                = require("E2E_API_URL");
    public static final String USER_POOL_ID           = require("COGNITO_USER_POOL_ID");
    public static final String CLIENT_ID              = require("COGNITO_CLIENT_ID");
    public static final String REGION                 = optional("AWS_DEFAULT_REGION", "ap-southeast-2");
    public static final String USERS_TABLE            = optional("USERS_TABLE_NAME", "CatalystFlow_Users");
    public static final String REVIEWS_TABLE          = require("REVIEWS_TABLE_NAME");
    public static final String REVIEW_CYCLES_TABLE    = require("REVIEW_CYCLES_TABLE_NAME");
    public static final String REVIEW_TEMPLATES_TABLE = require("REVIEW_TEMPLATES_TABLE_NAME");

    /**
     * Unique suffix derived from the CodeBuild build ID (or a random UUID when running locally).
     * Prevents collisions when multiple pipeline runs execute concurrently against the same stack.
     */
    public static final String RUN_ID;

    static {
        String buildId = System.getenv("CODEBUILD_BUILD_ID");
        if (buildId != null && !buildId.isBlank()) {
            String sanitised = buildId.replaceAll("[^a-zA-Z0-9]", "");
            RUN_ID = sanitised.substring(Math.max(0, sanitised.length() - 8));
        } else {
            RUN_ID = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
    }

    private E2EConfig() {}

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Required E2E env var is not set: " + name +
                "\nSet it in buildspec-e2e.yml or your local shell before running E2E tests."
            );
        }
        return value;
    }

    private static String optional(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}

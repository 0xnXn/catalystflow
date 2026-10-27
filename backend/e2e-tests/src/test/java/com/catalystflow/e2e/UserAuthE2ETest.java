package com.catalystflow.e2e;

import com.catalystflow.e2e.support.E2EExtension;
import com.catalystflow.e2e.support.TestFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * E2E tests for authentication mechanics.
 *
 * These tests verify that the API Gateway + Lambda auth pipeline correctly
 * accepts valid Cognito tokens and rejects all variants of invalid tokens.
 *
 * Deliberately separate from RBAC tests — authentication (who are you?)
 * is a different concern from authorisation (what can you do?).
 */
@ExtendWith(E2EExtension.class)
@DisplayName("Authentication E2E")
class UserAuthE2ETest {

    private static TestFixtures fixtures;

    @BeforeAll
    static void init(org.junit.jupiter.api.extension.ExtensionContext ctx) {
        fixtures = E2EExtension.fixtures(ctx);
    }

    // ── Health check (public endpoint) ────────────────────────────────────────

    @Test
    @DisplayName("GET /users/health — no token — 200 (public endpoint)")
    void healthEndpoint_isPublic() {
        given()
        .when()
            .get("/users/health")
        .then()
            .statusCode(200);
    }

    // ── Missing / malformed token ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /users — no Authorization header — 401")
    void noToken_returns401() {
        given()
        .when()
            .get("/users")
        .then()
            .statusCode(401);
    }

    @Test
    @DisplayName("GET /users — empty Bearer value — 401")
    void emptyBearerToken_returns401() {
        given()
            .header("Authorization", "Bearer ")
        .when()
            .get("/users")
        .then()
            .statusCode(401);
    }

    @Test
    @DisplayName("GET /users — malformed token (not a JWT) — 401")
    void malformedToken_returns401() {
        given()
            .header("Authorization", "Bearer this.is.notavalidjwt")
        .when()
            .get("/users")
        .then()
            .statusCode(401);
    }

    @Test
    @DisplayName("GET /users — random Base64 payload (no groups claim) — 401")
    void tokenWithNoGroupsClaim_returns401() {
        // A structurally valid JWT-looking string whose payload has no cognito:groups
        String fakeToken = "eyJhbGciOiJSUzI1NiJ9"
                + ".eyJzdWIiOiJmYWtlLXVzZXIiLCJleHAiOjk5OTk5OTk5OTl9"
                + ".invalidsignature";
        given()
            .header("Authorization", "Bearer " + fakeToken)
        .when()
            .get("/users")
        .then()
            .statusCode(401);
    }

    @Test
    @DisplayName("GET /users — wrong scheme (Basic instead of Bearer) — 401")
    void wrongAuthScheme_returns401() {
        given()
            .header("Authorization", "Basic dXNlcjpwYXNz")
        .when()
            .get("/users")
        .then()
            .statusCode(401);
    }

    // ── Valid real Cognito tokens ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /users — valid ADMIN Cognito token — 200")
    void validAdminToken_listUsers_returns200() {
        given()
            .header("Authorization", "Bearer " + fixtures.admin.token())
        .when()
            .get("/users")
        .then()
            .statusCode(200)
            .body("users",  notNullValue());
    }

    @Test
    @DisplayName("GET /users/{id} — valid MANAGER Cognito token — 200 (any authenticated user can read a profile)")
    void validManagerToken_getUser_returns200() {
        given()
            .header("Authorization", "Bearer " + fixtures.manager.token())
        .when()
            .get("/users/" + fixtures.employee.userId())
        .then()
            .statusCode(200)
            .body("userId", equalTo(fixtures.employee.userId()));
    }

    @Test
    @DisplayName("GET /users/{id} — valid EMPLOYEE Cognito token — 200 (own profile)")
    void validEmployeeToken_getOwnProfile_returns200() {
        given()
            .header("Authorization", "Bearer " + fixtures.employee.token())
        .when()
            .get("/users/" + fixtures.employee.userId())
        .then()
            .statusCode(200)
            .body("userId",    equalTo(fixtures.employee.userId()))
            .body("email",     equalTo(fixtures.employee.email()))
            .body("role",      equalTo("EMPLOYEE"));
    }

    @Test
    @DisplayName("POST /users — valid MANAGER token on ADMIN-only endpoint — 403")
    void validManagerToken_adminOnlyEndpoint_returns403() {
        given()
            .header("Authorization", "Bearer " + fixtures.manager.token())
            .contentType("application/json")
            .body("{\"email\":\"x@x.com\",\"firstName\":\"X\",\"lastName\":\"X\"," +
                  "\"role\":\"EMPLOYEE\",\"position\":\"Dev\",\"status\":\"Active\"}")
        .when()
            .post("/users")
        .then()
            .statusCode(403);
    }
}

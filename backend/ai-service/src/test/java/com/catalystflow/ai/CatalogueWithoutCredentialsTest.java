package com.catalystflow.ai;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static io.restassured.RestAssured.given;

@QuarkusTest
@TestProfile(CatalogueWithoutCredentialsTest.NoAssistance.class)
class CatalogueWithoutCredentialsTest {
    public static class NoAssistance implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() { return Map.of("gemini.api-key", ""); }
    }
    @Test void servesEmployeeMetadataWithNoModelCredentials() {
        given().get("/ai-review/disciplines").then().statusCode(200);
    }
}

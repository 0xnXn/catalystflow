package com.catalystflow.e2e;

import com.catalystflow.e2e.support.E2EExtension;
import com.catalystflow.e2e.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import static io.restassured.RestAssured.given;

@ExtendWith(E2EExtension.class)
@EnabledIfEnvironmentVariable(named = "ASSISTANCE_ENABLED", matches = "false")
class DisabledFeaturesE2ETest {
    @Test
    void catalogueRemainsAvailable(ExtensionContext context) {
        TestFixtures f = E2EExtension.fixtures(context);
        given().header("Authorization", "Bearer " + f.employee.token())
            .get("/ai-review/disciplines").then().statusCode(200);
    }
    @Test
    void assistanceActionsRemainDisabled(ExtensionContext context) {
        TestFixtures f = E2EExtension.fixtures(context);
        given().header("Authorization", "Bearer " + f.admin.token())
            .contentType("application/json").body("{}")
            .post("/ai-review/analyze").then().statusCode(403);
    }
    @Test
    void emailActionsRemainDisabled(ExtensionContext context) {
        TestFixtures f = E2EExtension.fixtures(context);
        given().header("Authorization", "Bearer " + f.admin.token())
            .contentType("application/json").body("{}")
            .post("/notifications/reminders/manual").then().statusCode(403);
    }
}

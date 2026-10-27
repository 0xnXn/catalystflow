package com.catalystflow.review.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.util.Map;
import java.util.Optional;

/**
 * Cross-service read-only lookup of users from the UsersTable.
 *
 * <p>Used by review-service to validate {@code subjectUserId} / {@code authorUserId}
 * exist and are not archived before creating PEER_360 assignments. We intentionally
 * read DynamoDB directly rather than calling user-service over HTTP — fewer hops,
 * no API Gateway round-trip, and the IAM scope is read-only so blast radius is small.
 *
 * <p>The new IAM permission lives in {@code template.yaml}:
 * {@code DynamoDBReadPolicy: TableName: !Ref UsersTable}.
 */
@ApplicationScoped
public class UserLookup {

    private static final Logger LOG = Logger.getLogger(UserLookup.class);

    @Inject
    DynamoDbClient dynamoDb;

    @ConfigProperty(name = "users.table-name")
    String usersTableName;

    /**
     * Fetch a user by Cognito {@code userId} ({@code sub}). Returns empty if not found.
     */
    public Optional<UserSummary> get(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }

        GetItemResponse response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(usersTableName)
                .key(Map.of("userId", AttributeValue.builder().s(userId).build()))
                .build());

        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(toSummary(response.item()));
    }

    /** Returns true if the user exists and is Active. */
    public boolean existsActive(String userId) {
        return get(userId).map(u -> "Active".equalsIgnoreCase(u.status())).orElse(false);
    }

    private UserSummary toSummary(Map<String, AttributeValue> item) {
        return new UserSummary(
                str(item, "userId"),
                str(item, "status"),
                str(item, "role"),
                str(item, "reportingTo")
        );
    }

    private static String str(Map<String, AttributeValue> item, String key) {
        AttributeValue v = item.get(key);
        return v == null ? null : v.s();
    }

    /**
     * Lightweight projection of the User record — only the fields review-service
     * needs to validate assignments.
     */
    public record UserSummary(String userId, String status, String role, String reportingTo) {}
}

package com.catalystflow.e2e.support;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import java.util.Map;

/**
 * Wraps the Cognito Admin APIs needed to bootstrap and tear down E2E test users.
 *
 * All operations use the Admin API (server-side, no client secret required).
 * The CodeBuild IAM role must have the following permissions on the user pool:
 *   cognito-idp:AdminCreateUser
 *   cognito-idp:AdminSetUserPassword
 *   cognito-idp:AdminAddUserToGroup
 *   cognito-idp:AdminInitiateAuth
 *   cognito-idp:AdminGetUser
 *   cognito-idp:AdminDeleteUser
 */
public class CognitoAuthManager implements AutoCloseable {

    private final CognitoIdentityProviderClient cognito;
    private final String userPoolId;
    private final String clientId;

    public CognitoAuthManager(String userPoolId, String clientId, String region) {
        this.userPoolId = userPoolId;
        this.clientId   = clientId;
        this.cognito    = CognitoIdentityProviderClient.builder()
                .region(Region.of(region))
                .httpClientBuilder(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.builder())
                .build();
    }

    /**
     * Creates a user in Cognito, sets a permanent password, and adds them to a group.
     * Returns the Cognito sub (userId).
     */
    public String createUser(String email,
                             String firstName,
                             String lastName,
                             String group,
                             String permanentPassword) {
        cognito.adminCreateUser(AdminCreateUserRequest.builder()
                .userPoolId(userPoolId)
                .username(email)
                .temporaryPassword(permanentPassword + "Tmp!")
                .messageAction(MessageActionType.SUPPRESS)
                .userAttributes(
                        AttributeType.builder().name("email").value(email).build(),
                        AttributeType.builder().name("email_verified").value("true").build(),
                        AttributeType.builder().name("name").value(firstName + " " + lastName).build()
                )
                .build());

        // Bypass the force-change-password challenge so tests can authenticate immediately
        cognito.adminSetUserPassword(AdminSetUserPasswordRequest.builder()
                .userPoolId(userPoolId)
                .username(email)
                .password(permanentPassword)
                .permanent(true)
                .build());

        cognito.adminAddUserToGroup(AdminAddUserToGroupRequest.builder()
                .userPoolId(userPoolId)
                .username(email)
                .groupName(group)
                .build());

        return getCognitoSub(email);
    }

    /**
     * Forces a permanent password on a user already created via another mechanism
     * (e.g. created by POST /users which leaves them in FORCE_CHANGE_PASSWORD state).
     */
    public void setPermanentPassword(String email, String password) {
        cognito.adminSetUserPassword(AdminSetUserPasswordRequest.builder()
                .userPoolId(userPoolId)
                .username(email)
                .password(password)
                .permanent(true)
                .build());
    }

    /**
     * Authenticates a user using the ADMIN_USER_PASSWORD_AUTH flow and returns the ID token.
     * The user pool client must have ALLOW_ADMIN_USER_PASSWORD_AUTH in its ExplicitAuthFlows.
     */
    public String authenticate(String email, String password) {
        AdminInitiateAuthResponse response = cognito.adminInitiateAuth(
                AdminInitiateAuthRequest.builder()
                        .userPoolId(userPoolId)
                        .clientId(clientId)
                        .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                        .authParameters(Map.of(
                                "USERNAME", email,
                                "PASSWORD", password
                        ))
                        .build()
        );
        return response.authenticationResult().idToken();
    }

    /**
     * Returns the Cognito sub (userId) for the given username (email).
     */
    public String getCognitoSub(String email) {
        return cognito.adminGetUser(AdminGetUserRequest.builder()
                        .userPoolId(userPoolId)
                        .username(email)
                        .build())
                .userAttributes().stream()
                .filter(a -> "sub".equals(a.name()))
                .findFirst()
                .map(AttributeType::value)
                .orElseThrow(() -> new IllegalStateException("No sub attribute for user: " + email));
    }

    /**
     * Deletes a user from Cognito. Swallows UserNotFoundException so teardown is idempotent.
     */
    public void deleteUser(String email) {
        try {
            cognito.adminDeleteUser(AdminDeleteUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(email)
                    .build());
        } catch (UserNotFoundException ignored) {
        }
    }

    @Override
    public void close() {
        cognito.close();
    }
}

package com.catalystflow.user;

import com.catalystflow.user.model.CreateUserRequest;
import com.catalystflow.user.model.UpdateUserRequest;
import com.catalystflow.user.model.User;
import com.catalystflow.user.service.UserService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@QuarkusTest
class UserServiceTest {

    @Inject
    UserService userService;

    @InjectMock
    DynamoDbClient mockDynamoDb;

    @InjectMock
    DynamoDbAsyncClient mockDynamoDbAsync;

    @InjectMock
    CognitoIdentityProviderClient mockCognito;

    // -------------------------------------------------------------------------
    // listUsers
    // -------------------------------------------------------------------------

    @Test
    void testListUsers_ReturnsMappedItems() {
        when(mockDynamoDb.scan(any(ScanRequest.class)))
                .thenReturn(ScanResponse.builder().items(buildDynamoUser("sub-1", "user@test.com", "Test", "User", "EMPLOYEE", null)).build());

        List<User> users = userService.listUsers();

        assertEquals(1, users.size());
        assertEquals("sub-1", users.get(0).getUserId());
        assertEquals("user@test.com", users.get(0).getEmail());
    }

    @Test
    void testListUsersByRole_ReturnsOnlyMatchingRole() {
        when(mockDynamoDb.scan(any(ScanRequest.class)))
                .thenReturn(ScanResponse.builder().items(buildDynamoUser("sub-1", "user@test.com", "Test", "User", "MANAGER", null)).build());

        List<User> users = userService.listUsersByRole("MANAGER");

        assertEquals(1, users.size());
        assertEquals("MANAGER", users.get(0).getRole());
    }

    @Test
    void testListUsers_EmptyTable_ReturnsEmptyList() {
        when(mockDynamoDb.scan(any(ScanRequest.class)))
                .thenReturn(ScanResponse.builder().build());

        List<User> users = userService.listUsers();

        assertTrue(users.isEmpty());
    }

    // -------------------------------------------------------------------------
    // getUser
    // -------------------------------------------------------------------------

    @Test
    void testGetUser_Found() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(buildDynamoUser("sub-1", "user@test.com", "Test", "User", "EMPLOYEE", "manager-id"))
                        .build());

        Optional<User> user = userService.getUser("sub-1");

        assertTrue(user.isPresent());
        assertEquals("sub-1", user.get().getUserId());
        assertEquals("manager-id", user.get().getReportingTo());
    }

    @Test
    void testGetUser_NotFound_ReturnsEmpty() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        Optional<User> user = userService.getUser("missing");

        assertTrue(user.isEmpty());
    }

    // -------------------------------------------------------------------------
    // createUser
    // -------------------------------------------------------------------------

    @Test
    void testCreateUser_CreatesInCognitoAndDynamoDB() {
        UserType cognitoUser = UserType.builder()
                .attributes(AttributeType.builder().name("sub").value("new-sub-id").build())
                .build();
        when(mockCognito.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenReturn(AdminCreateUserResponse.builder().user(cognitoUser).build());
        when(mockCognito.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class)))
                .thenReturn(AdminAddUserToGroupResponse.builder().build());
        when(mockDynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(PutItemResponse.builder().build());

        CreateUserRequest request = new CreateUserRequest()
                .email("new@example.com")
                .firstName("New")
                .lastName("User")
                .role("EMPLOYEE")
                .position("Engineer")
                .status("Active")
                .seniority("Engineer")
                .discipline("Software & DevOps Engineering");

        User created = userService.createUser(request);

        assertEquals("new-sub-id", created.getUserId());
        assertEquals("new@example.com", created.getEmail());
        assertEquals("EMPLOYEE", created.getRole());
        assertEquals("Engineer", created.getSeniority());
        assertEquals("Software & DevOps Engineering", created.getDiscipline());
        // updatedAt is server-set on create and powers the Role Mgmt "Last Modified" column.
        assertNotNull(created.getUpdatedAt());
        verify(mockCognito, times(1)).adminCreateUser(any(AdminCreateUserRequest.class));
        verify(mockCognito, times(1)).adminAddUserToGroup(any(AdminAddUserToGroupRequest.class));
        verify(mockDynamoDb, times(1)).putItem(argThat((PutItemRequest req) ->
                "Engineer".equals(req.item().get("seniority").s()) &&
                "Software & DevOps Engineering".equals(req.item().get("discipline").s()) &&
                req.item().get("updatedAt") != null && req.item().get("updatedAt").s() != null
        ));
    }

    @Test
    void testCreateUser_AdminRole_AddsToAdminGroup() {
        UserType cognitoUser = UserType.builder()
                .attributes(AttributeType.builder().name("sub").value("admin-sub").build())
                .build();
        when(mockCognito.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenReturn(AdminCreateUserResponse.builder().user(cognitoUser).build());
        when(mockCognito.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class)))
                .thenReturn(AdminAddUserToGroupResponse.builder().build());
        when(mockDynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(PutItemResponse.builder().build());

        CreateUserRequest request = new CreateUserRequest()
                .email("admin@example.com")
                .firstName("Admin")
                .lastName("User")
                .role("ADMIN")
                .position("Administrator")
                .status("Active");

        User created = userService.createUser(request);

        assertEquals("ADMIN", created.getRole());
        verify(mockCognito).adminAddUserToGroup(argThat((AdminAddUserToGroupRequest req) ->
                "ADMIN".equals(req.groupName())
        ));
    }

    // -------------------------------------------------------------------------
    // updateUser
    // -------------------------------------------------------------------------

    @Test
    void testUpdateUser_UpdatesNameAndRole() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(buildDynamoUser("u1", "user@test.com", "Old", "Name", "EMPLOYEE", null))
                        .build());
        when(mockCognito.adminUpdateUserAttributes(any(AdminUpdateUserAttributesRequest.class)))
                .thenReturn(AdminUpdateUserAttributesResponse.builder().build());
        when(mockCognito.adminRemoveUserFromGroup(any(AdminRemoveUserFromGroupRequest.class)))
                .thenReturn(AdminRemoveUserFromGroupResponse.builder().build());
        when(mockCognito.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class)))
                .thenReturn(AdminAddUserToGroupResponse.builder().build());
        when(mockDynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(UpdateItemResponse.builder().build());

        UpdateUserRequest request = new UpdateUserRequest()
                .firstName("New")
                .lastName("Name")
                .role("ADMIN");

        userService.updateUser("u1", request);

        verify(mockCognito, times(1)).adminUpdateUserAttributes(any(AdminUpdateUserAttributesRequest.class));
        verify(mockCognito, times(1)).adminRemoveUserFromGroup(any(AdminRemoveUserFromGroupRequest.class));
        verify(mockCognito, times(1)).adminAddUserToGroup(any(AdminAddUserToGroupRequest.class));
        // Every update must bump updatedAt — even a role-only change registers
        // in the Role Mgmt "Last Modified" column.
        verify(mockDynamoDb, times(1)).updateItem(argThat((UpdateItemRequest req) ->
                req.expressionAttributeValues().containsKey(":updatedAt") &&
                req.updateExpression().contains("updatedAt")
        ));
    }

    @Test
    void testUpdateUser_UserNotFound_ThrowsException() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());

        UpdateUserRequest request = new UpdateUserRequest()
                .firstName("Name");

        assertThrows(NoSuchElementException.class, () -> userService.updateUser("missing", request));
    }

    // -------------------------------------------------------------------------
    // getTeam
    // -------------------------------------------------------------------------

    @Test
    void testGetTeam_ReturnsDirectReports() {
        when(mockDynamoDb.scan(any(ScanRequest.class)))
                .thenReturn(ScanResponse.builder()
                        .items(buildDynamoUser("sub-1", "user@test.com", "Test", "User", "EMPLOYEE", "manager-id"))
                        .build());

        List<User> team = userService.getTeam("manager-id");

        assertEquals(1, team.size());
        assertEquals("sub-1", team.get(0).getUserId());
        assertEquals("manager-id", team.get(0).getReportingTo());
    }

    @Test
    void testGetTeam_NoDirectReports_ReturnsEmptyList() {
        when(mockDynamoDb.scan(any(ScanRequest.class)))
                .thenReturn(ScanResponse.builder().build());

        List<User> team = userService.getTeam("manager-with-no-reports");

        assertTrue(team.isEmpty());
    }

    @Test
    void testListUsersByRole_EmptyResult_ReturnsEmptyList() {
        when(mockDynamoDb.scan(any(ScanRequest.class)))
                .thenReturn(ScanResponse.builder().build());

        List<User> users = userService.listUsersByRole("ADMIN");

        assertTrue(users.isEmpty());
    }

    // -------------------------------------------------------------------------
    // assignTeamMembers
    // -------------------------------------------------------------------------

    @Test
    void testAssignTeamMembers_UpdatesReportingTo() {
        when(mockDynamoDbAsync.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(UpdateItemResponse.builder().build()));

        userService.assignTeamMembers("manager-id", List.of("user-1", "user-2"));

        verify(mockDynamoDbAsync, times(2)).updateItem(any(UpdateItemRequest.class));
        verify(mockDynamoDb, never()).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void testAssignTeamMembers_EmptyList_IsNoOp() {
        userService.assignTeamMembers("manager-id", List.of());
        verify(mockDynamoDbAsync, never()).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void testAssignTeamMembers_NullList_IsNoOp() {
        userService.assignTeamMembers("manager-id", null);
        verify(mockDynamoDbAsync, never()).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void testAssignTeamMembers_PartialFailure_Throws500ListingFailedMemberIds() {
        // user-good succeeds, user-bad fails. Mockito calls the matcher with null
        // during stub disambiguation, so the predicate must be null-defensive.
        when(mockDynamoDbAsync.updateItem(argThat((UpdateItemRequest req) ->
                req != null && "user-good".equals(req.key().get("userId").s()))))
                .thenReturn(CompletableFuture.completedFuture(UpdateItemResponse.builder().build()));
        when(mockDynamoDbAsync.updateItem(argThat((UpdateItemRequest req) ->
                req != null && "user-bad".equals(req.key().get("userId").s()))))
                .thenReturn(CompletableFuture.failedFuture(
                        software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
                                .builder().message("simulated").build()));

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> userService.assignTeamMembers("manager-id", List.of("user-good", "user-bad")));

        assertEquals(500, ex.getResponse().getStatus());
        // Failed memberId must be in the message for the caller to retry.
        assertTrue(ex.getMessage().contains("user-bad"),
                "Expected message to list failed memberId 'user-bad', got: " + ex.getMessage());
        // Successful memberId must NOT be in the failed list.
        assertFalse(ex.getMessage().contains("user-good"),
                "Did not expect successful memberId 'user-good' in failure message: " + ex.getMessage());
        // Both attempted — partial-failure semantics, not fail-fast.
        verify(mockDynamoDbAsync, times(2)).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void testAssignTeamMembers_AllFail_ThrowsListingAllMemberIds() {
        when(mockDynamoDbAsync.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
                                .builder().message("simulated").build()));

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> userService.assignTeamMembers("manager-id", List.of("u1", "u2", "u3")));

        assertEquals(500, ex.getResponse().getStatus());
        assertTrue(ex.getMessage().contains("u1"));
        assertTrue(ex.getMessage().contains("u2"));
        assertTrue(ex.getMessage().contains("u3"));
        verify(mockDynamoDbAsync, times(3)).updateItem(any(UpdateItemRequest.class));
    }

    @Test
    void testCreateUser_WithOptionalFieldsAndTeamMembers() {
        UserType cognitoUser = UserType.builder()
                .attributes(AttributeType.builder().name("sub").value("new-sub-id").build())
                .build();
        when(mockCognito.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenReturn(AdminCreateUserResponse.builder().user(cognitoUser).build());
        when(mockCognito.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class)))
                .thenReturn(AdminAddUserToGroupResponse.builder().build());
        when(mockDynamoDb.putItem(any(PutItemRequest.class)))
                .thenReturn(PutItemResponse.builder().build());
        // assignTeamMembers now fans out via the async client
        when(mockDynamoDbAsync.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(UpdateItemResponse.builder().build()));

        CreateUserRequest request = new CreateUserRequest()
                .email("new@example.com")
                .firstName("New")
                .lastName("User")
                .role("MANAGER")
                .position("Manager")
                .status("Active")
                .reportingTo("admin-1")
                .hireDate("2023-01-01")
                .teamMembers(List.of("u1", "u2"));

        User created = userService.createUser(request);

        assertEquals("MANAGER", created.getRole());
        assertEquals("admin-1", created.getReportingTo());
        assertEquals("2023-01-01", created.getHireDate());
        verify(mockCognito, times(1)).adminCreateUser(any(AdminCreateUserRequest.class));
        verify(mockDynamoDb, times(1)).putItem(any(PutItemRequest.class));
        // Sync updateItem is no longer used for team-member assignment.
        verify(mockDynamoDb, never()).updateItem(any(UpdateItemRequest.class));
        verify(mockDynamoDbAsync, times(2)).updateItem(any(UpdateItemRequest.class)); // inside assignTeamMembers
    }

    // -------------------------------------------------------------------------
    // createUser — rollback / failure modes
    //
    // Cognito + DynamoDB is a dual-write. If anything between AdminCreateUser
    // and the final return throws, we must call AdminDeleteUser to compensate;
    // otherwise the Cognito user is orphaned and will collide on the next retry.
    // -------------------------------------------------------------------------

    @Test
    void testCreateUser_PutItemFails_RollsBackCognitoUser() {
        UserType cognitoUser = UserType.builder()
                .attributes(AttributeType.builder().name("sub").value("orphan-sub").build())
                .build();
        when(mockCognito.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenReturn(AdminCreateUserResponse.builder().user(cognitoUser).build());
        when(mockCognito.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class)))
                .thenReturn(AdminAddUserToGroupResponse.builder().build());
        when(mockDynamoDb.putItem(any(PutItemRequest.class)))
                .thenThrow(software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
                        .builder().message("simulated DDB outage").build());

        CreateUserRequest request = new CreateUserRequest()
                .email("rollback@example.com")
                .firstName("Roll")
                .lastName("Back")
                .role("EMPLOYEE")
                .position("Engineer")
                .status("Active");

        assertThrows(RuntimeException.class, () -> userService.createUser(request));

        verify(mockCognito, times(1)).adminDeleteUser(argThat((AdminDeleteUserRequest req) ->
                "rollback@example.com".equals(req.username())
        ));
    }

    @Test
    void testCreateUser_GroupAddFails_RollsBackCognitoUser() {
        UserType cognitoUser = UserType.builder()
                .attributes(AttributeType.builder().name("sub").value("orphan-sub").build())
                .build();
        when(mockCognito.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenReturn(AdminCreateUserResponse.builder().user(cognitoUser).build());
        when(mockCognito.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class)))
                .thenThrow(software.amazon.awssdk.services.cognitoidentityprovider.model.ResourceNotFoundException
                        .builder().message("group missing").build());

        CreateUserRequest request = new CreateUserRequest()
                .email("rollback2@example.com")
                .firstName("Roll")
                .lastName("Back")
                .role("EMPLOYEE")
                .position("Engineer")
                .status("Active");

        assertThrows(RuntimeException.class, () -> userService.createUser(request));

        verify(mockCognito, times(1)).adminDeleteUser(argThat((AdminDeleteUserRequest req) ->
                "rollback2@example.com".equals(req.username())
        ));
        verify(mockDynamoDb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void testCreateUser_RollbackAlsoFails_OriginalExceptionStillPropagates() {
        UserType cognitoUser = UserType.builder()
                .attributes(AttributeType.builder().name("sub").value("orphan-sub").build())
                .build();
        when(mockCognito.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenReturn(AdminCreateUserResponse.builder().user(cognitoUser).build());
        when(mockCognito.adminAddUserToGroup(any(AdminAddUserToGroupRequest.class)))
                .thenReturn(AdminAddUserToGroupResponse.builder().build());
        // Original downstream failure — this is what should propagate.
        RuntimeException downstream = software.amazon.awssdk.services.dynamodb.model
                .ResourceNotFoundException.builder()
                .message("downstream put failed").build();
        when(mockDynamoDb.putItem(any(PutItemRequest.class))).thenThrow(downstream);
        // Rollback also fails — should not mask the original cause.
        when(mockCognito.adminDeleteUser(any(AdminDeleteUserRequest.class)))
                .thenThrow(InternalErrorException.builder().message("rollback ITSELF failed").build());

        CreateUserRequest request = new CreateUserRequest()
                .email("orphan@example.com")
                .firstName("Or")
                .lastName("Phan")
                .role("EMPLOYEE")
                .position("Engineer")
                .status("Active");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> userService.createUser(request));
        // The original DDB exception should propagate — not the rollback exception.
        assertSame(downstream, thrown);
        verify(mockCognito, times(1)).adminDeleteUser(any(AdminDeleteUserRequest.class));
    }

    @Test
    void testCreateUser_UsernameExists_DoesNotAttemptRollback() {
        // AdminCreateUser itself throws — no Cognito user was created, nothing to roll back.
        when(mockCognito.adminCreateUser(any(AdminCreateUserRequest.class)))
                .thenThrow(UsernameExistsException.builder().message("User already exists").build());

        CreateUserRequest request = new CreateUserRequest()
                .email("existing@example.com")
                .firstName("Already")
                .lastName("Here")
                .role("EMPLOYEE")
                .position("Engineer")
                .status("Active");

        assertThrows(UsernameExistsException.class, () -> userService.createUser(request));

        verify(mockCognito, never()).adminDeleteUser(any(AdminDeleteUserRequest.class));
        verify(mockCognito, never()).adminAddUserToGroup(any(AdminAddUserToGroupRequest.class));
        verify(mockDynamoDb, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void testUpdateUser_WithOptionalFieldsAndTeamMembers() {
        when(mockDynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder()
                        .item(buildDynamoUser("u1", "user@test.com", "Old", "Name", "EMPLOYEE", null))
                        .build());
        when(mockDynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(UpdateItemResponse.builder().build());
        // assignTeamMembers now fans out via the async client
        when(mockDynamoDbAsync.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(UpdateItemResponse.builder().build()));

        UpdateUserRequest request = new UpdateUserRequest()
                .firstName("First")
                .lastName("Last")
                .position("Manager")
                .status("Active")
                .reportingTo("admin-1")
                .hireDate("2023-01-01")
                .seniority("Senior Engineer")
                .discipline("Software & DevOps Engineering")
                .role("MANAGER")
                .teamMembers(List.of("u2"));

        userService.updateUser("u1", request);

        // Sync updateItem is now used only for the user's own row, not team-member assignment.
        verify(mockDynamoDb, times(1)).updateItem(any(UpdateItemRequest.class));
        verify(mockDynamoDb).updateItem(argThat((UpdateItemRequest req) ->
                req.updateExpression().contains("seniority = :seniority") &&
                req.updateExpression().contains("discipline = :discipline") &&
                "Senior Engineer".equals(req.expressionAttributeValues().get(":seniority").s()) &&
                "Software & DevOps Engineering".equals(req.expressionAttributeValues().get(":discipline").s())
        ));
        // assignTeamMembers fans out through async client — one teammate, one async call.
        verify(mockDynamoDbAsync, times(1)).updateItem(any(UpdateItemRequest.class));
    }

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    private Map<String, AttributeValue> buildDynamoUser(String userId, String email,
                                                          String firstName, String lastName,
                                                          String role, String reportingTo) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("userId",    AttributeValue.builder().s(userId).build());
        item.put("email",     AttributeValue.builder().s(email).build());
        item.put("firstName", AttributeValue.builder().s(firstName).build());
        item.put("lastName",  AttributeValue.builder().s(lastName).build());
        item.put("role",      AttributeValue.builder().s(role).build());
        if (reportingTo != null) {
            item.put("reportingTo", AttributeValue.builder().s(reportingTo).build());
        }
        return item;
    }
}

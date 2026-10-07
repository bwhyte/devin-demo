package com.security.bank.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.security.bank.support.IntegrationTestSupport;

/** /admin/** — authorisation rules and current behaviour. */
class AdminEndpointsIntegrationTest extends IntegrationTestSupport {

    @ParameterizedTest
    @ValueSource(strings = { "/admin/getAllUser", "/admin/getUserByName/alice", "/admin/account/getActiveAccountsList",
	    "/admin/account/getInActiveAccountsList", "/admin/accountList/ByAccountType/SAVINGS",
	    "/admin/accountList/ByBranchType/BOB" })
    void readEndpointsAre403ForAnonymousAndCustomerAnd200ForAdmin(String url) {
	registerCustomer("alice");
	createAdmin("root");
	String customer = login("alice");
	String admin = login("root");

	assertThat(get(url, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	assertThat(get(url, customer).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	assertThat(get(url, admin).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void writeEndpointsAre403ForCustomer() {
	long aliceId = registerCustomer("alice");
	String customer = login("alice");
	OpenedAccount account = openAccount(customer, aliceId, "SAVINGS", 1000);

	assertThat(call(HttpMethod.DELETE, "/admin/deleteUser/" + aliceId, customer, null).getStatusCode())
		.isEqualTo(HttpStatus.FORBIDDEN);
	assertThat(call(HttpMethod.PUT, "/admin/account/deactivate?userId=" + aliceId + "&accountId=" + account.id(),
		customer, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	assertThat(call(HttpMethod.PUT, "/admin/account/activate?userId=" + aliceId + "&accountId=" + account.id(),
		customer, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	assertThat(count("user")).isEqualTo(1);
    }

    @Test
    void addAdminIs403ForAnonymous() {
	ResponseEntity<String> response = call(HttpMethod.POST, "/admin/add", null,
		Map.of("username", "mallory", "password", PASSWORD, "name", "M"));

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	assertThat(count("user")).isZero();
    }

    /** Current behaviour: /admin/add has no @PreAuthorize, so any authenticated customer can mint an admin. */
    @Test
    void addAdminIsAllowedForAnyAuthenticatedCustomerAndTheNewAdminHasFullAdminAccess() {
	registerCustomer("alice");
	String customer = login("alice");

	ResponseEntity<String> response = call(HttpMethod.POST, "/admin/add", customer,
		Map.of("username", "mallory", "password", PASSWORD, "name", "Mallory"));

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	assertThat(row("select r.role_name from user u join role r on u.roles_id = r.id where u.username = 'mallory'")
		.get("role_name")).isEqualTo("ROLE_ADMIN");
	assertThat(get("/admin/getAllUser", login("mallory")).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getAllUserReturnsEveryUserIncludingPasswordHashesAndAuthorities() {
	registerCustomer("alice");
	createAdmin("root");

	JsonNode users = read(get("/admin/getAllUser", login("root")));

	assertThat(users).hasSize(2);
	JsonNode alice = users.get(0);
	assertThat(alice.get("username").asText()).isEqualTo("alice");
	assertThat(alice.get("password").asText()).startsWith("$2a$10$");
	assertThat(alice.get("roles").get("roleName").asText()).isEqualTo("ROLE_CUSTOMER");
	assertThat(alice.get("authorities").get(0).get("authority").asText()).isEqualTo("ROLE_CUSTOMER");
	assertThat(alice.get("enabled").asBoolean()).isTrue();
    }

    @Test
    void getUserByNameReturnsTheUserOr500WhenMissing() {
	registerCustomer("alice");
	createAdmin("root");
	String admin = login("root");

	assertThat(read(get("/admin/getUserByName/alice", admin)).get("username").asText()).isEqualTo("alice");
	assertThat(get("/admin/getUserByName/nobody", admin).getStatusCode())
		.isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void deleteUserCascadesToAccountsCardsNomineesInvestmentsAndRole() {
	long aliceId = registerCustomer("alice");
	String customer = login("alice");
	OpenedAccount account = openAccount(customer, aliceId, "SAVINGS", 1000);
	call(HttpMethod.POST, "/invest/now?accountId=" + account.id(), customer,
		Map.of("investmentType", "GOLD", "amount", 10, "duration", "1y"));
	createAdmin("root");

	ResponseEntity<String> response = call(HttpMethod.DELETE, "/admin/deleteUser/" + aliceId, login("root"), null);

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	assertThat(response.getBody()).isEqualTo("Deleted Successfully");
	assertThat(count("account")).isZero();
	assertThat(count("card")).isZero();
	assertThat(count("nominee")).isZero();
	assertThat(count("investment")).isZero();
	assertThat(count("role")).isEqualTo(1);
    }

    @Test
    void deleteUnknownUserReturns200WithErrorText() {
	createAdmin("root");

	ResponseEntity<String> response = call(HttpMethod.DELETE, "/admin/deleteUser/999", login("root"), null);

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	assertThat(response.getBody()).isEqualTo("Error in deletion");
    }

    @Test
    void deactivateAndActivateAccountLifecycle() {
	long aliceId = registerCustomer("alice");
	OpenedAccount account = openAccount(login("alice"), aliceId, "SAVINGS", 1000);
	createAdmin("root");
	String admin = login("root");
	String query = "?userId=" + aliceId + "&accountId=" + account.id();

	ResponseEntity<String> activeAlready = call(HttpMethod.PUT, "/admin/account/activate" + query, admin, null);
	assertThat(activeAlready.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
	assertThat(activeAlready.getBody()).isEqualTo("ERROR");

	ResponseEntity<String> deactivated = call(HttpMethod.PUT, "/admin/account/deactivate" + query, admin, null);
	assertThat(deactivated.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
	assertThat(deactivated.getBody()).isEqualTo("Deactivated Account for User with id: " + aliceId);
	assertThat(row("select status from account").get("status")).isEqualTo("INACTIVE");
	assertThat(read(get("/admin/account/getInActiveAccountsList", admin))).hasSize(1);
	assertThat(read(get("/admin/account/getActiveAccountsList", admin))).isEmpty();

	ResponseEntity<String> activated = call(HttpMethod.PUT, "/admin/account/activate" + query, admin, null);
	assertThat(activated.getBody()).isEqualTo("Activated Account for User with id: " + aliceId);
	assertThat(row("select status from account").get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void deactivateWithAccountOfAnotherUserReportsSuccessButChangesNothing() {
	long aliceId = registerCustomer("alice");
	long bobId = registerCustomer("bob");
	OpenedAccount bobAccount = openAccount(login("bob"), bobId, "SAVINGS", 1000);
	createAdmin("root");

	ResponseEntity<String> response = call(HttpMethod.PUT,
		"/admin/account/deactivate?userId=" + aliceId + "&accountId=" + bobAccount.id(), login("root"), null);

	assertThat(response.getBody()).isEqualTo("Deactivated Account for User with id: " + aliceId);
	assertThat(row("select status from account").get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void deactivateWithUnknownIdsReturnsError() {
	createAdmin("root");

	ResponseEntity<String> response = call(HttpMethod.PUT, "/admin/account/deactivate?userId=998&accountId=999",
		login("root"), null);

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
	assertThat(response.getBody()).isEqualTo("ERROR");
    }

    @Test
    void accountListsFilterByTypeAndBranch() {
	long aliceId = registerCustomer("alice");
	String customer = login("alice");
	openAccount(customer, aliceId, "SAVINGS", 1000);
	openAccount(customer, aliceId, "PPF", 2000);
	createAdmin("root");
	String admin = login("root");

	JsonNode savings = read(get("/admin/accountList/ByAccountType/SAVINGS", admin));
	assertThat(savings).hasSize(1);
	assertThat(savings.get(0).get("branch").asText()).isEqualTo("BOB");
	assertThat(read(get("/admin/accountList/ByBranchType/SBI", admin)).get(0).get("accountType").asText())
		.isEqualTo("PPF");
	assertThat(read(get("/admin/accountList/ByBranchType/HDFC", admin))).isEmpty();
	assertThat(read(get("/admin/account/getActiveAccountsList", admin))).hasSize(2);
    }

    @Test
    void invalidEnumInPathReturns400() {
	createAdmin("root");

	assertThat(get("/admin/accountList/ByAccountType/GOLD", login("root")).getStatusCode())
		.isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

package com.security.bank.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.security.bank.support.IntegrationTestSupport;

/** /account/** — account opening, queries, nominee and KYC updates. */
class AccountEndpointsIntegrationTest extends IntegrationTestSupport {

    @ParameterizedTest
    @CsvSource({ "SAVINGS, 2.7, BOB, DEBIT_GLOBAL, 40000", "CURRENT, 5.2, ICIC, CREDIT_PREMIUM, 50000",
	    "SALARY, 4.1, HDFC, CREDIT_MASTER, 75000" })
    void openingAnAccountAppliesTypeDefaultsAndIssuesACard(String type, double rate, String branch, String cardType,
	    double limit) {
	long aliceId = registerCustomer("alice");

	ResponseEntity<String> response = call(HttpMethod.POST, "/account/create/" + aliceId, login("alice"),
		accountRequest(type, 5000));

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	assertThat(response.getBody()).isNull();
	Map<String, Object> account = row("select * from account");
	assertThat(account.get("account_type")).isEqualTo(type);
	assertThat(((Number) account.get("interest_rate")).doubleValue()).isCloseTo(rate, within(0.001));
	assertThat(account.get("branch")).isEqualTo(branch);
	assertThat(account.get("status")).isEqualTo("ACTIVE");
	assertThat(((Number) account.get("balance")).doubleValue()).isEqualTo(5000);
	assertThat(account.get("proof")).isEqualTo("PAN");
	assertThat(((Number) account.get("account_number")).longValue()).isBetween(10_000_000L, 99_999_999L);
	assertThat(((Number) account.get("user_id")).longValue()).isEqualTo(aliceId);
	assertThat(account.get("opening_date")).isNotNull();

	Map<String, Object> card = row("select * from card");
	assertThat(card.get("id")).isEqualTo(account.get("card_id"));
	assertThat(card.get("card_type")).isEqualTo(cardType);
	assertThat(((Number) card.get("daily_limit")).doubleValue()).isEqualTo(limit);
	assertThat(((Number) card.get("pin")).longValue()).isEqualTo(1122L);
	assertThat(card.get("card_holder_name")).isEqualTo("Name of alice");
	assertThat(card.get("status")).isEqualTo("ACTIVE");
	assertThat(((Number) card.get("cvv")).intValue()).isBetween(100, 999);
	Instant expiry = ((Timestamp) card.get("expiry_date")).toInstant();
	assertThat(Duration.between(Instant.now(), expiry).toDays()).isBetween(1820L, 1830L);

	Map<String, Object> nominee = row("select * from nominee");
	assertThat(nominee.get("id")).isEqualTo(account.get("nominee_id"));
	assertThat(nominee.get("name")).isEqualTo("Jane Doe");
	assertThat(nominee.get("relation")).isEqualTo("SPOUSE");
	assertThat(((Number) nominee.get("age")).intValue()).isEqualTo(32);
    }

    @Test
    void openingAPpfAccountIssuesNoCard() {
	long aliceId = registerCustomer("alice");

	openAccount(login("alice"), aliceId, "PPF", 5000);

	Map<String, Object> account = row("select * from account");
	assertThat(account.get("account_type")).isEqualTo("PPF");
	assertThat(account.get("branch")).isEqualTo("SBI");
	assertThat(((Number) account.get("interest_rate")).doubleValue()).isCloseTo(7.4, within(0.001));
	assertThat(account.get("card_id")).isNull();
	assertThat(count("card")).isZero();
    }

    @Test
    void openingWithUnknownAccountTypeReturns500AndPersistsNothing() {
	long aliceId = registerCustomer("alice");

	ResponseEntity<String> response = call(HttpMethod.POST, "/account/create/" + aliceId, login("alice"),
		accountRequest("FIXED", 5000));

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	assertThat(count("account")).isZero();
	assertThat(count("card")).isZero();
	assertThat(count("nominee")).isZero();
    }

    @Test
    void openingForUnknownUserIdReturns500ButLeavesOrphanCardAndNominee() {
	registerCustomer("alice");

	ResponseEntity<String> response = call(HttpMethod.POST, "/account/create/999", login("alice"),
		accountRequest("SAVINGS", 5000));

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	assertThat(count("account")).isZero();
	assertThat(count("card")).isEqualTo(1);
	assertThat(count("nominee")).isEqualTo(1);
    }

    @Test
    void customerCanOpenAnAccountForAnotherUser() {
	registerCustomer("alice");
	long bobId = registerCustomer("bob");

	call(HttpMethod.POST, "/account/create/" + bobId, login("alice"), accountRequest("SAVINGS", 5000));

	assertThat(((Number) row("select user_id from account").get("user_id")).longValue()).isEqualTo(bobId);
    }

    @Test
    void accountEndpointsAre403ForAnonymousAndForAdmins() {
	long aliceId = registerCustomer("alice");
	OpenedAccount account = openAccount(login("alice"), aliceId, "SAVINGS", 5000);
	createAdmin("root");
	String admin = login("root");

	for (String url : new String[] { "/account/all/" + aliceId, "/account/balance?accountNumber=" + account.accountNumber(),
		"/account/nominee?accountNumber=" + account.accountNumber(),
		"/account/getKycDetails?accountNumber=" + account.accountNumber(),
		"/account/getAccount/summary?accountNumber=" + account.accountNumber() }) {
	    assertThat(get(url, null).getStatusCode()).as("anonymous " + url).isEqualTo(HttpStatus.FORBIDDEN);
	    assertThat(get(url, admin).getStatusCode()).as("admin " + url).isEqualTo(HttpStatus.FORBIDDEN);
	}
	assertThat(call(HttpMethod.POST, "/account/create/" + aliceId, admin, accountRequest("SAVINGS", 1)).getStatusCode())
		.isEqualTo(HttpStatus.FORBIDDEN);
	assertThat(call(HttpMethod.PUT, "/account/updateNominee/" + account.id(), null, Map.of("name", "X")).getStatusCode())
		.isEqualTo(HttpStatus.FORBIDDEN);
	assertThat(call(HttpMethod.PUT, "/account/updateKyc/" + account.id(), admin, Map.of("name", "X")).getStatusCode())
		.isEqualTo(HttpStatus.FORBIDDEN);
	assertThat(count("account")).isEqualTo(1);
    }

    @Test
    void listAccountsReturnsCardNomineeAndOwnerIncludingPasswordHash() {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, "SAVINGS", 5000);

	JsonNode accounts = read(get("/account/all/" + aliceId, token));

	assertThat(accounts).hasSize(1);
	JsonNode first = accounts.get(0);
	assertThat(first.get("accountNumber").asLong()).isEqualTo(account.accountNumber());
	assertThat(first.get("card").get("cardNumber").asLong()).isEqualTo(account.cardNumber());
	assertThat(first.get("card").get("cvv").asInt()).isBetween(100, 999);
	assertThat(first.get("card").get("pin").asLong()).isEqualTo(1122L);
	assertThat(first.get("nominee").get("name").asText()).isEqualTo("Jane Doe");
	assertThat(first.get("user").get("username").asText()).isEqualTo("alice");
	assertThat(first.get("user").get("password").asText()).startsWith("$2a$10$");
	assertThat(first.get("user").has("accountList")).isFalse();
    }

    @Test
    void customerCanReadAnotherUsersAccountsBalanceNomineeKycAndSummary() {
	long aliceId = registerCustomer("alice");
	OpenedAccount aliceAccount = openAccount(login("alice"), aliceId, "SAVINGS", 5000);
	registerCustomer("bob");
	String bob = login("bob");
	long n = aliceAccount.accountNumber();

	assertThat(read(get("/account/all/" + aliceId, bob))).hasSize(1);
	assertThat(get("/account/balance?accountNumber=" + n, bob).getBody()).isEqualTo("5000.0");
	assertThat(get("/account/nominee?accountNumber=" + n, bob).getStatusCode()).isEqualTo(HttpStatus.OK);
	assertThat(read(get("/account/getKycDetails?accountNumber=" + n, bob)).get("username").asText()).isEqualTo("alice");
	assertThat(get("/account/getAccount/summary?accountNumber=" + n, bob).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void listAccountsForUnknownUserReturns500() {
	registerCustomer("alice");

	assertThat(get("/account/all/999", login("alice")).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void balanceReturnsAPlainNumber() {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, "SAVINGS", 1234.5);

	ResponseEntity<String> response = get("/account/balance?accountNumber=" + account.accountNumber(), token);

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	assertThat(response.getBody()).isEqualTo("1234.5");
    }

    @Test
    void queriesForUnknownAccountNumberReturn500AndMissingParameterReturns400() {
	registerCustomer("alice");
	String token = login("alice");

	for (String path : new String[] { "balance", "nominee", "getKycDetails", "getAccount/summary" }) {
	    assertThat(get("/account/" + path + "?accountNumber=1", token).getStatusCode()).as(path)
		    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	    assertThat(get("/account/" + path, token).getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
	}
    }

    @Test
    void nomineeIsReturnedAndCanBeUpdated() {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, "SAVINGS", 5000);

	JsonNode nominee = read(get("/account/nominee?accountNumber=" + account.accountNumber(), token));
	assertThat(nominee.get("name").asText()).isEqualTo("Jane Doe");
	assertThat(nominee.get("accountNumber").asLong()).isEqualTo(12345678L);
	assertThat(nominee.get("gender").asText()).isEqualTo("FEMALE");

	Map<String, Object> update = new LinkedHashMap<>();
	update.put("name", "John Roe");
	update.put("relation", "BROTHER");
	update.put("accountNumber", 87654321);
	update.put("gender", "MALE");
	update.put("age", 40);
	ResponseEntity<String> response = call(HttpMethod.PUT, "/account/updateNominee/" + account.id(), token, update);

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	Map<String, Object> stored = row("select * from nominee");
	assertThat(stored.get("name")).isEqualTo("John Roe");
	assertThat(stored.get("relation")).isEqualTo("BROTHER");
	assertThat(((Number) stored.get("account_number")).longValue()).isEqualTo(87654321L);
	assertThat(stored.get("gender")).isEqualTo("MALE");
	assertThat(((Number) stored.get("age")).intValue()).isEqualTo(40);
	assertThat(count("nominee")).isEqualTo(1);
    }

    @Test
    void updateNomineeWithPartialBodyOverwritesMissingFieldsWithNullAndZero() {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, "SAVINGS", 5000);

	call(HttpMethod.PUT, "/account/updateNominee/" + account.id(), token, Map.of("name", "Only Name"));

	Map<String, Object> stored = row("select * from nominee");
	assertThat(stored.get("name")).isEqualTo("Only Name");
	assertThat(stored.get("relation")).isNull();
	assertThat(stored.get("account_number")).isNull();
	assertThat(((Number) stored.get("age")).intValue()).isZero();
    }

    @Test
    void updateNomineeForUnknownAccountReturns500() {
	registerCustomer("alice");

	assertThat(call(HttpMethod.PUT, "/account/updateNominee/999", login("alice"), Map.of("name", "X")).getStatusCode())
		.isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void kycDetailsReturnOwnerWithoutAccountAndInvestmentListsButWithPasswordHash() {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, "SAVINGS", 5000);

	JsonNode kyc = read(get("/account/getKycDetails?accountNumber=" + account.accountNumber(), token));

	assertThat(kyc.get("username").asText()).isEqualTo("alice");
	assertThat(kyc.get("address").asText()).isEqualTo("1 Main St");
	assertThat(kyc.get("identityProof").asText()).isEqualTo("PASSPORT");
	assertThat(kyc.get("password").asText()).startsWith("$2a$10$");
	assertThat(kyc.get("accountList").isNull()).isTrue();
	assertThat(kyc.get("investmentList").isNull()).isTrue();
	assertThat(count("account")).isEqualTo(1);
    }

    @Test
    void updateKycAppliesNonEmptyFieldsOnly() {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, "SAVINGS", 5000);
	Map<String, Object> kyc = new LinkedHashMap<>();
	kyc.put("name", "");
	kyc.put("address", "2 High St");
	kyc.put("number", null);
	kyc.put("identityProof", "");

	ResponseEntity<String> response = call(HttpMethod.PUT, "/account/updateKyc/" + account.id(), token, kyc);

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	Map<String, Object> user = row("select * from user");
	assertThat(user.get("name")).isEqualTo("Name of alice");
	assertThat(user.get("address")).isEqualTo("2 High St");
	assertThat(((Number) user.get("number")).longValue()).isEqualTo(5550100L);
	assertThat(user.get("identity_proof")).isEqualTo("PASSPORT");
    }

    @Test
    void updateKycWithAllFieldsUpdatesTheOwner() {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, "SAVINGS", 5000);

	call(HttpMethod.PUT, "/account/updateKyc/" + account.id(), token,
		Map.of("name", "Alice Smith", "address", "3 Low St", "number", 5559999L, "identityProof", "DL"));

	Map<String, Object> user = row("select * from user");
	assertThat(user.get("name")).isEqualTo("Alice Smith");
	assertThat(user.get("address")).isEqualTo("3 Low St");
	assertThat(((Number) user.get("number")).longValue()).isEqualTo(5559999L);
	assertThat(user.get("identity_proof")).isEqualTo("DL");
    }

    @Test
    void updateKycWithAMissingStringFieldReturns500AndChangesNothing() {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, "SAVINGS", 5000);

	ResponseEntity<String> response = call(HttpMethod.PUT, "/account/updateKyc/" + account.id(), token,
		Map.of("address", "2 High St"));

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	assertThat(row("select address from user").get("address")).isEqualTo("1 Main St");
    }

    @Test
    void summaryHidesOwnerWithoutUnlinkingItInTheDatabase() {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, "CURRENT", 5000);

	JsonNode summary = read(get("/account/getAccount/summary?accountNumber=" + account.accountNumber(), token));

	assertThat(summary.get("accountNumber").asLong()).isEqualTo(account.accountNumber());
	assertThat(summary.get("accountType").asText()).isEqualTo("CURRENT");
	assertThat(summary.get("user").isNull()).isTrue();
	assertThat(((Number) row("select user_id from account").get("user_id")).longValue()).isEqualTo(aliceId);
    }
}

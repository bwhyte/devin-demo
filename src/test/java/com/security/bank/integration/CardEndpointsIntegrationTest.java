package com.security.bank.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.security.bank.support.IntegrationTestSupport;

/** /card/** — blocking, applying for and configuring cards. */
class CardEndpointsIntegrationTest extends IntegrationTestSupport {

    @Test
    void blockingViaGetDeletesTheCardAndUnlinksItFromTheAccount() {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, "SAVINGS", 5000);

	ResponseEntity<String> response = get(
		"/card/block?accountNumber=" + account.accountNumber() + "&cardNumber=" + account.cardNumber(), token);

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	assertThat(response.getBody()).isEqualTo("Card Blocked Successfully");
	assertThat(row("select card_id from account").get("card_id")).isNull();
	assertThat(count("card")).isZero();
    }

    @Test
    void blockingWithACardFromAnotherAccountReturns500AndChangesNothing() {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount first = openAccount(token, aliceId, "SAVINGS", 5000);
	OpenedAccount second = openAccount(token, aliceId, "CURRENT", 5000);

	ResponseEntity<String> response = get(
		"/card/block?accountNumber=" + first.accountNumber() + "&cardNumber=" + second.cardNumber(), token);

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	assertThat(count("card")).isEqualTo(2);
    }

    @Test
    void blockingOnAnAccountWithoutCardReturns500() {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount ppf = openAccount(token, aliceId, "PPF", 5000);
	OpenedAccount savings = openAccount(token, aliceId, "SAVINGS", 5000);

	assertThat(get("/card/block?accountNumber=" + ppf.accountNumber() + "&cardNumber=" + savings.cardNumber(), token)
		.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void blockingWithUnknownNumbersReturns500() {
	registerCustomer("alice");

	assertThat(get("/card/block?accountNumber=1&cardNumber=2", login("alice")).getStatusCode())
		.isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void customerCanBlockAnotherUsersCard() {
	long aliceId = registerCustomer("alice");
	OpenedAccount account = openAccount(login("alice"), aliceId, "SAVINGS", 5000);
	registerCustomer("bob");

	ResponseEntity<String> response = get(
		"/card/block?accountNumber=" + account.accountNumber() + "&cardNumber=" + account.cardNumber(),
		login("bob"));

	assertThat(response.getBody()).isEqualTo("Card Blocked Successfully");
	assertThat(count("card")).isZero();
    }

    @Test
    void applyingForACardWhenOneExistsReturns500() {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, "SAVINGS", 5000);

	ResponseEntity<String> response = call(HttpMethod.POST, "/card/apply/new?accountNumber=" + account.accountNumber(),
		token, Map.of("cardHolderName", "A", "cardType", "DEBIT_CLASSIC", "pin", 1234));

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	assertThat(count("card")).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({ "DEBIT_CLASSIC, DEBIT_CLASSIC, 20000", "CREDIT_PREMIUM, CREDIT_PREMIUM, 50000",
	    "CREDIT_MASTER, CREDIT_MASTER, 75000", "DEBIT_GLOBAL, DEBIT_GLOBAL, 40000", "PLATINUM, DEBIT_GLOBAL, 40000" })
    void applyingForACardOnAnAccountWithoutCardIssuesItWithTypeLimits(String requested, String issued, double limit) {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, "PPF", 5000);

	ResponseEntity<String> response = call(HttpMethod.POST, "/card/apply/new?accountNumber=" + account.accountNumber(),
		token, Map.of("cardHolderName", "Alice Card", "cardType", requested, "pin", 4321));

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	assertThat(response.getBody()).isEqualTo("New Card Allocated to account wih Number: " + account.accountNumber());
	Map<String, Object> card = row("select * from card");
	assertThat(card.get("id")).isEqualTo(row("select card_id from account").get("card_id"));
	assertThat(card.get("card_type")).isEqualTo(issued);
	assertThat(((Number) card.get("daily_limit")).doubleValue()).isEqualTo(limit);
	assertThat(((Number) card.get("pin")).longValue()).isEqualTo(4321L);
	assertThat(card.get("card_holder_name")).isEqualTo("Alice Card");
	assertThat(card.get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void applyingAfterBlockingIssuesANewCard() {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, "SAVINGS", 5000);
	get("/card/block?accountNumber=" + account.accountNumber() + "&cardNumber=" + account.cardNumber(), token);

	ResponseEntity<String> response = call(HttpMethod.POST, "/card/apply/new?accountNumber=" + account.accountNumber(),
		token, Map.of("cardHolderName", "A", "cardType", "CREDIT_MASTER", "pin", 1));

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	assertThat(row("select card_number from card").get("card_number")).isNotEqualTo(account.cardNumber());
    }

    @ParameterizedTest
    @CsvSource({ "SAVINGS, 50000, 50000", "SAVINGS, 50001, 40000", "SAVINGS, 0, 40000", "CURRENT, 75000, 75000",
	    "CURRENT, 75001, 50000", "SALARY, 100000, 100000", "SALARY, 100001, 75000" })
    void dailyLimitChangesAreAcceptedUpToATypeCeilingAndSilentlyIgnoredAbove(String accountType, double requested,
	    double expected) {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, accountType, 5000);

	ResponseEntity<String> response = call(HttpMethod.PUT, "/card/setting?cardNumber=" + account.cardNumber(), token,
		Map.of("dailyLimit", requested));

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	assertThat(((Number) row("select daily_limit from card").get("daily_limit")).doubleValue()).isEqualTo(expected);
    }

    @Test
    void debitClassicCeilingIs40000() {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, "PPF", 5000);
	call(HttpMethod.POST, "/card/apply/new?accountNumber=" + account.accountNumber(), token,
		Map.of("cardHolderName", "A", "cardType", "DEBIT_CLASSIC", "pin", 1));
	Object cardNumber = row("select card_number from card").get("card_number");

	call(HttpMethod.PUT, "/card/setting?cardNumber=" + cardNumber, token, Map.of("dailyLimit", 40001));
	assertThat(((Number) row("select daily_limit from card").get("daily_limit")).doubleValue()).isEqualTo(20000);
	call(HttpMethod.PUT, "/card/setting?cardNumber=" + cardNumber, token, Map.of("dailyLimit", 40000));
	assertThat(((Number) row("select daily_limit from card").get("daily_limit")).doubleValue()).isEqualTo(40000);
    }

    @Test
    void settingUpdatesPinWhenProvidedAndKeepsItOtherwise() {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, "SAVINGS", 5000);

	call(HttpMethod.PUT, "/card/setting?cardNumber=" + account.cardNumber(), token, Map.of("pin", 9999));
	assertThat(((Number) row("select pin from card").get("pin")).longValue()).isEqualTo(9999L);

	call(HttpMethod.PUT, "/card/setting?cardNumber=" + account.cardNumber(), token, Map.of("dailyLimit", 100));
	assertThat(((Number) row("select pin from card").get("pin")).longValue()).isEqualTo(9999L);
    }

    @Test
    void settingForUnknownCardReturns500() {
	registerCustomer("alice");

	assertThat(call(HttpMethod.PUT, "/card/setting?cardNumber=1", login("alice"), Map.of("pin", 1)).getStatusCode())
		.isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void cardEndpointsAre403ForAnonymousAndForAdmins() {
	long aliceId = registerCustomer("alice");
	OpenedAccount account = openAccount(login("alice"), aliceId, "SAVINGS", 5000);
	createAdmin("root");
	String admin = login("root");
	String block = "/card/block?accountNumber=" + account.accountNumber() + "&cardNumber=" + account.cardNumber();

	for (String token : new String[] { null, admin }) {
	    assertThat(get(block, token).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	    assertThat(call(HttpMethod.POST, "/card/apply/new?accountNumber=" + account.accountNumber(), token,
		    Map.of("cardType", "DEBIT_CLASSIC")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	    assertThat(call(HttpMethod.PUT, "/card/setting?cardNumber=" + account.cardNumber(), token,
		    Map.of("pin", 1)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}
	assertThat(count("card")).isEqualTo(1);
	assertThat(((Number) row("select pin from card").get("pin")).longValue()).isEqualTo(1122L);
    }
}

package com.security.bank.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.security.bank.support.IntegrationTestSupport;

/** /invest/now. */
class InvestmentEndpointsIntegrationTest extends IntegrationTestSupport {

    @ParameterizedTest
    @CsvSource({ "GOLD, GOLD, Low, 12.0, BuyNow", "STOCKS, STOCKS, high, 20.0, StockWay",
	    "MUTUAL_FUND, MUTUAL_FUND, Moderate, 12.3, ray fund", "FIXED_DEPOSITS, FIXED_DEPOSITS, Low, 9.2, PST",
	    "CRYPTO, FIXED_DEPOSITS, Low, 9.2, PST" })
    void investingRecordsAnInvestmentWithTypeDefaults(String requested, String type, String risk, double returns,
	    String company) {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, "SAVINGS", 5000);

	ResponseEntity<String> response = call(HttpMethod.POST, "/invest/now?accountId=" + account.id(), token,
		Map.of("investmentType", requested, "amount", 1000, "duration", "12 months"));

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	assertThat(response.getBody()).isEqualTo("Investment successful");
	Map<String, Object> investment = row("select * from investment");
	assertThat(investment.get("investment_type")).isEqualTo(type);
	assertThat(investment.get("risk")).isEqualTo(risk);
	assertThat(((Number) investment.get("returns")).doubleValue()).isCloseTo(returns, within(0.001));
	assertThat(investment.get("company_name")).isEqualTo(company);
	assertThat(((Number) investment.get("amount")).doubleValue()).isEqualTo(1000);
	assertThat(investment.get("duration")).isEqualTo("12 months");
	assertThat(((Number) investment.get("user_id")).longValue()).isEqualTo(aliceId);
    }

    @Test
    void investingDoesNotDebitTheAccountBalance() {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, "SAVINGS", 5000);

	call(HttpMethod.POST, "/invest/now?accountId=" + account.id(), token,
		Map.of("investmentType", "GOLD", "amount", 4000, "duration", "1y"));
	call(HttpMethod.POST, "/invest/now?accountId=" + account.id(), token,
		Map.of("investmentType", "GOLD", "amount", 4000, "duration", "1y"));

	assertThat(count("investment")).isEqualTo(2);
	assertThat(get("/account/balance?accountNumber=" + account.accountNumber(), token).getBody()).isEqualTo("5000.0");
    }

    @Test
    void investingTheWholeBalanceOrMoreReturns500() {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, "SAVINGS", 5000);

	for (int amount : new int[] { 5000, 5001 }) {
	    assertThat(call(HttpMethod.POST, "/invest/now?accountId=" + account.id(), token,
		    Map.of("investmentType", "GOLD", "amount", amount, "duration", "1y")).getStatusCode())
		    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}
	assertThat(count("investment")).isZero();
    }

    @Test
    void investingFromUnknownAccountReturns500() {
	registerCustomer("alice");

	assertThat(call(HttpMethod.POST, "/invest/now?accountId=999", login("alice"),
		Map.of("investmentType", "GOLD", "amount", 1, "duration", "1y")).getStatusCode())
		.isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void customerCanInvestFromAnotherUsersAccountAndTheInvestmentIsOwnedByTheAccountHolder() {
	long aliceId = registerCustomer("alice");
	OpenedAccount account = openAccount(login("alice"), aliceId, "SAVINGS", 5000);
	registerCustomer("bob");

	ResponseEntity<String> response = call(HttpMethod.POST, "/invest/now?accountId=" + account.id(), login("bob"),
		Map.of("investmentType", "STOCKS", "amount", 100, "duration", "1y"));

	assertThat(response.getBody()).isEqualTo("Investment successful");
	assertThat(((Number) row("select user_id from investment").get("user_id")).longValue()).isEqualTo(aliceId);
    }

    @Test
    void investEndpointIs403ForAnonymousAndForAdmins() {
	long aliceId = registerCustomer("alice");
	OpenedAccount account = openAccount(login("alice"), aliceId, "SAVINGS", 5000);
	createAdmin("root");

	for (String token : new String[] { null, login("root") }) {
	    assertThat(call(HttpMethod.POST, "/invest/now?accountId=" + account.id(), token,
		    Map.of("investmentType", "GOLD", "amount", 1, "duration", "1y")).getStatusCode())
		    .isEqualTo(HttpStatus.FORBIDDEN);
	}
	assertThat(count("investment")).isZero();
    }

    @Test
    void listingAccountsAfterInvestingStillSerialises() {
	long aliceId = registerCustomer("alice");
	String token = login("alice");
	OpenedAccount account = openAccount(token, aliceId, "SAVINGS", 5000);
	call(HttpMethod.POST, "/invest/now?accountId=" + account.id(), token,
		Map.of("investmentType", "GOLD", "amount", 1, "duration", "1y"));

	ResponseEntity<String> response = get("/account/all/" + aliceId, token);

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	assertThat(read(response).get(0).get("user").get("investmentList")).hasSize(1);
    }
}

package com.security.bank.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.bank.dto.AdminDto;
import com.security.bank.service.UserService;

/**
 * Base class for characterisation tests that drive the real HTTP stack (embedded Tomcat, Spring Security filter
 * chain, error dispatch) against the in-memory H2 "test" profile. Status codes asserted here are what a client
 * observes today, including 500s produced by unhandled exceptions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    protected static final String PASSWORD = "secret123";

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserService userService;

    @BeforeEach
    void resetDatabase() {
	jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
	for (String table : List.of("investment", "account", "card", "nominee", "user", "role")) {
	    jdbc.execute("TRUNCATE TABLE " + table + " RESTART IDENTITY");
	}
	jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    protected long registerCustomer(String username) {
	Map<String, Object> body = new LinkedHashMap<>();
	body.put("username", username);
	body.put("password", PASSWORD);
	body.put("name", "Name of " + username);
	body.put("address", "1 Main St");
	body.put("number", 5550100L);
	body.put("identityProof", "PASSPORT");
	ResponseEntity<String> response = call(HttpMethod.POST, "/user/register", null, body);
	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	return userId(username);
    }

    protected long createAdmin(String username) {
	AdminDto admin = new AdminDto();
	admin.setUsername(username);
	admin.setPassword(PASSWORD);
	admin.setName("Admin " + username);
	userService.registerAdmin(admin);
	return userId(username);
    }

    protected long userId(String username) {
	return jdbc.queryForObject("select id from user where username = ?", Long.class, username);
    }

    protected String login(String username) {
	ResponseEntity<String> response = call(HttpMethod.POST, "/user/login", null,
		Map.of("username", username, "password", PASSWORD));
	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	return read(response).get("jwtToken").asText();
    }

    protected ResponseEntity<String> call(HttpMethod method, String url, String token, Object body) {
	HttpHeaders headers = new HttpHeaders();
	headers.setContentType(MediaType.APPLICATION_JSON);
	if (token != null) {
	    headers.setBearerAuth(token);
	}
	return rest.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

    protected ResponseEntity<String> get(String url, String token) {
	return call(HttpMethod.GET, url, token, null);
    }

    protected ResponseEntity<String> callWithAuthorizationHeader(String url, String authorizationHeader) {
	HttpHeaders headers = new HttpHeaders();
	headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
	return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    protected JsonNode read(ResponseEntity<String> response) {
	try {
	    return objectMapper.readTree(response.getBody());
	} catch (JsonProcessingException e) {
	    throw new IllegalStateException("Response is not JSON: " + response.getBody(), e);
	}
    }

    protected static Map<String, Object> accountRequest(String accountType, double balance) {
	Map<String, Object> nominee = new LinkedHashMap<>();
	nominee.put("name", "Jane Doe");
	nominee.put("accountNumber", 12345678);
	nominee.put("relation", "SPOUSE");
	nominee.put("age", 32);
	nominee.put("gender", "FEMALE");
	Map<String, Object> body = new LinkedHashMap<>();
	body.put("accountType", accountType);
	body.put("balance", balance);
	body.put("proof", "PAN");
	body.put("nominee", nominee);
	return body;
    }

    protected OpenedAccount openAccount(String token, long userId, String accountType, double balance) {
	ResponseEntity<String> response = call(HttpMethod.POST, "/account/create/" + userId, token,
		accountRequest(accountType, balance));
	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	Map<String, Object> row = jdbc.queryForMap("select a.id as id, a.account_number as account_number, "
		+ "c.card_number as card_number from account a left join card c on a.card_id = c.id "
		+ "where a.user_id = ? order by a.id desc limit 1", userId);
	Number cardNumber = (Number) row.get("card_number");
	return new OpenedAccount(((Number) row.get("id")).longValue(),
		((Number) row.get("account_number")).longValue(), cardNumber == null ? null : cardNumber.longValue());
    }

    protected Map<String, Object> row(String sql, Object... args) {
	return jdbc.queryForMap(sql, args);
    }

    protected int count(String table) {
	return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    public record OpenedAccount(long id, long accountNumber, Long cardNumber) {
    }
}

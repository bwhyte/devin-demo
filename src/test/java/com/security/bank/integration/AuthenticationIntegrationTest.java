package com.security.bank.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.security.bank.support.IntegrationTestSupport;
import com.security.bank.support.JwtTestTokens;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

/** /user/register, /user/login and the JWT filter as seen by an HTTP client. */
class AuthenticationIntegrationTest extends IntegrationTestSupport {

    private static final String PROTECTED_URL = "/account/all/1";

    @Test
    void registerReturns201WithEmptyBodyAndStoresBcryptHashWithCustomerRole() {
	ResponseEntity<String> response = call(HttpMethod.POST, "/user/register", null,
		Map.of("username", "alice", "password", PASSWORD, "name", "Alice"));

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	assertThat(response.getBody()).isNull();
	Map<String, Object> user = row("select u.*, r.role_name from user u join role r on u.roles_id = r.id");
	assertThat(user.get("username")).isEqualTo("alice");
	assertThat(user.get("name")).isEqualTo("Alice");
	assertThat((String) user.get("password")).startsWith("$2a$10$").isNotEqualTo(PASSWORD);
	assertThat(user.get("role_name")).isEqualTo("ROLE_CUSTOMER");
    }

    @Test
    void registerIgnoresRoleFieldsSuppliedInTheBody() {
	call(HttpMethod.POST, "/user/register", null, Map.of("username", "eve", "password", PASSWORD, "name", "Eve",
		"userType", "ADMIN", "roles", Map.of("roleName", "ROLE_ADMIN")));

	assertThat(row("select r.role_name from user u join role r on u.roles_id = r.id").get("role_name"))
		.isEqualTo("ROLE_CUSTOMER");
    }

    @Test
    void registerCreatesANewRoleRowPerUser() {
	registerCustomer("alice");
	registerCustomer("bob");

	assertThat(count("role")).isEqualTo(2);
    }

    @Test
    void registerAcceptsDuplicateUsernamesAndLoginThenFailsWith403() {
	Map<String, Object> body = Map.of("username", "alice", "password", PASSWORD, "name", "Alice");
	assertThat(call(HttpMethod.POST, "/user/register", null, body).getStatusCode()).isEqualTo(HttpStatus.CREATED);
	assertThat(call(HttpMethod.POST, "/user/register", null, body).getStatusCode()).isEqualTo(HttpStatus.CREATED);

	assertThat(count("user")).isEqualTo(2);
	ResponseEntity<String> login = call(HttpMethod.POST, "/user/login", null,
		Map.of("username", "alice", "password", PASSWORD));
	assertThat(login.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void registerWithoutPasswordReturns500AndStoresNothing() {
	ResponseEntity<String> response = call(HttpMethod.POST, "/user/register", null, Map.of("username", "alice"));

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	assertThat(count("user")).isZero();
    }

    @Test
    void loginReturnsHs512TokenWithUsernameSubjectAndOneHourExpiry() {
	registerCustomer("alice");

	ResponseEntity<String> response = call(HttpMethod.POST, "/user/login", null,
		Map.of("username", "alice", "password", PASSWORD));

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	String token = read(response).get("jwtToken").asText();
	assertThat(read(response).size()).isEqualTo(1);
	Claims claims = JwtTestTokens.parse(token);
	assertThat(claims.getSubject()).isEqualTo("alice");
	assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isEqualTo(3_600_000L);
	assertThat(Jwts.parserBuilder().setSigningKey(JwtTestTokens.HARDCODED_SECRET.getBytes()).build()
		.parseClaimsJws(token).getHeader().getAlgorithm()).isEqualTo("HS512");
    }

    @Test
    void loginWithWrongPasswordReturns403WithEmptyBody() {
	registerCustomer("alice");

	ResponseEntity<String> response = call(HttpMethod.POST, "/user/login", null,
		Map.of("username", "alice", "password", "wrong"));

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	assertThat(response.getBody()).isNull();
    }

    @Test
    void loginWithUnknownUserReturns403() {
	ResponseEntity<String> response = call(HttpMethod.POST, "/user/login", null,
		Map.of("username", "nobody", "password", PASSWORD));

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getOnPublicLoginPathReturns405() {
	assertThat(get("/user/login", null).getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void protectedEndpointWithoutTokenReturns403WithEmptyBody() {
	ResponseEntity<String> response = get(PROTECTED_URL, null);

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	assertThat(response.getBody()).isNull();
    }

    @Test
    void unknownPathReturns403WithoutTokenAnd404WithToken() {
	registerCustomer("alice");
	String token = login("alice");

	assertThat(get("/does-not-exist", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	assertThat(get("/does-not-exist", token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void authorizationHeaderWithoutBearerPrefixIsIgnored() {
	registerCustomer("alice");
	String token = login("alice");

	assertThat(callWithAuthorizationHeader(PROTECTED_URL, token).getStatusCode())
		.isEqualTo(HttpStatus.FORBIDDEN);
	assertThat(callWithAuthorizationHeader(PROTECTED_URL, "bearer " + token).getStatusCode())
		.isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void malformedTokenReturns500() {
	assertThat(callWithAuthorizationHeader(PROTECTED_URL, "Bearer not-a-jwt").getStatusCode())
		.isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void tokenWithTamperedSignatureReturns500() {
	registerCustomer("alice");
	String token = login("alice");
	String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

	assertThat(get(PROTECTED_URL, tampered).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void tokenSignedWithAnotherKeyReturns500() {
	registerCustomer("alice");
	long now = System.currentTimeMillis();
	String token = JwtTestTokens.sign("alice", new java.util.Date(now), new java.util.Date(now + 60_000),
		JwtTestTokens.OTHER_SECRET);

	assertThat(get(PROTECTED_URL, token).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void expiredTokenReturns500() {
	long userId = registerCustomer("alice");

	assertThat(get("/account/all/" + userId, JwtTestTokens.expired("alice")).getStatusCode())
		.isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void tokenForgedWithTheSecretFromSourceCodeIsAccepted() {
	long userId = registerCustomer("alice");

	ResponseEntity<String> response = get("/account/all/" + userId,
		JwtTestTokens.forgeWithHardcodedSecret("alice"));

	assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	assertThat(response.getBody()).isEqualTo("[]");
    }

    @Test
    void validTokenForAUserThatNoLongerExistsReturns500() {
	registerCustomer("alice");
	String token = login("alice");
	jdbc.update("delete from user where username = 'alice'");

	assertThat(get(PROTECTED_URL, token).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void tokenStaysValidAfterTheUserIsRecreatedWithTheSameUsername() {
	registerCustomer("alice");
	String token = login("alice");
	jdbc.update("delete from user where username = 'alice'");
	long newId = registerCustomer("alice");

	assertThat(get("/account/all/" + newId, token).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}

package com.security.bank.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Date;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import com.security.bank.support.JwtTestTokens;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;

class JwtAuthenticationHelperTest {

    private final JwtAuthenticationHelper helper = new JwtAuthenticationHelper();

    private final UserDetails alice = User.withUsername("alice").password("x").authorities("ROLE_CUSTOMER").build();

    @Test
    void generatedTokenCarriesOnlySubjectIssuedAtAndOneHourExpiry() {
	String token = helper.generateToken(alice);

	Claims claims = helper.getClaimsFromToken(token);
	assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "iat", "exp");
	assertThat(helper.getUsernameFromToken(token)).isEqualTo("alice");
	assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isEqualTo(3_600_000L);
	assertThat(helper.isTokenExpired(token)).isFalse();
    }

    @Test
    void tokensAreSignedWithTheSecretHardcodedInTheClass() {
	assertThat(JwtTestTokens.parse(helper.generateToken(alice)).getSubject()).isEqualTo("alice");
	assertThat(helper.getUsernameFromToken(JwtTestTokens.forgeWithHardcodedSecret("anyone"))).isEqualTo("anyone");
    }

    @Test
    void expiredTokensThrowInsteadOfReportingExpiry() {
	String expired = JwtTestTokens.expired("alice");

	assertThatThrownBy(() -> helper.getUsernameFromToken(expired)).isInstanceOf(ExpiredJwtException.class);
	assertThatThrownBy(() -> helper.isTokenExpired(expired)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tokensSignedWithAnotherKeyOrMalformedAreRejected() {
	long now = System.currentTimeMillis();
	String foreign = JwtTestTokens.sign("alice", new Date(now), new Date(now + 60_000), JwtTestTokens.OTHER_SECRET);

	assertThatThrownBy(() -> helper.getUsernameFromToken(foreign)).isInstanceOf(SignatureException.class);
	assertThatThrownBy(() -> helper.getUsernameFromToken("not-a-jwt")).isInstanceOf(MalformedJwtException.class);
    }
}

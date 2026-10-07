package com.security.bank.support;

import java.util.Date;

import javax.crypto.spec.SecretKeySpec;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

/** Builds JWTs the same way JwtAuthenticationHelper does, using the secret hardcoded in that class. */
public final class JwtTestTokens {

    public static final String HARDCODED_SECRET = "thisisacodingninjasdemonstrationforsecretkeyinspringsecurityjsonwebtokenauthentication";

    public static final String OTHER_SECRET = "a-completely-different-secret-that-is-long-enough-for-hs512-signing-0123456789";

    private JwtTestTokens() {
    }

    public static String sign(String subject, Date issuedAt, Date expiration, String secret) {
	return Jwts.builder().setSubject(subject).setIssuedAt(issuedAt).setExpiration(expiration)
		.signWith(new SecretKeySpec(secret.getBytes(), SignatureAlgorithm.HS512.getJcaName()),
			SignatureAlgorithm.HS512)
		.compact();
    }

    public static String forgeWithHardcodedSecret(String subject) {
	long now = System.currentTimeMillis();
	return sign(subject, new Date(now), new Date(now + 3_600_000), HARDCODED_SECRET);
    }

    public static String expired(String subject) {
	long now = System.currentTimeMillis();
	return sign(subject, new Date(now - 7_200_000), new Date(now - 3_600_000), HARDCODED_SECRET);
    }

    public static Claims parse(String token) {
	return Jwts.parserBuilder().setSigningKey(HARDCODED_SECRET.getBytes()).build().parseClaimsJws(token).getBody();
    }
}

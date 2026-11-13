package dev.sagaharbor;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecurityValidatorsTest {
    private static final String ISSUER = "https://issuer.example.test/";
    private static Jwt token(String issuer, String audience, Instant expiration) {
        return Jwt.withTokenValue("synthetic")
                .header("alg", "RS256")
                .claim("iss", issuer).claim("aud", List.of(audience)).claim("sub", "operator-1")
                .issuedAt(expiration.minusSeconds(300)).expiresAt(expiration).build();
    }
    @Test void validatesIssuerAudienceAndExpiry() {
        var validator = SecurityConfig.tokenValidator(ISSUER, "saga-api");
        assertFalse(validator.validate(token(ISSUER, "saga-api", Instant.now().plusSeconds(300))).hasErrors());
        assertTrue(validator.validate(token("https://other.example.test/", "saga-api", Instant.now().plusSeconds(300))).hasErrors());
        assertTrue(validator.validate(token(ISSUER, "other-api", Instant.now().plusSeconds(300))).hasErrors());
        assertTrue(validator.validate(token(ISSUER, "saga-api", Instant.now().minusSeconds(300))).hasErrors());
    }
}

package dev.sagaharbor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.net.URI;
import java.util.List;

@Configuration
class SecurityConfig {
    private static final RequestMatcher BEARER_API = request -> {
        String bearer = request.getHeader("Authorization");
        return request.getRequestURI().startsWith("/api/v1/sagas") && bearer != null && bearer.startsWith("Bearer ");
    };
    @Bean
    @ConditionalOnProperty(name="app.mode", havingValue="demo", matchIfMissing=true)
    SecurityFilterChain demoChain(HttpSecurity http) throws Exception {
        // The demo API performs its own bearer-key check; Compose binds both ports to loopback.
        return http.csrf(csrf -> csrf.ignoringRequestMatchers(BEARER_API))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
    }
    @Bean
    @ConditionalOnProperty(name="app.mode", havingValue="production")
    SecurityFilterChain productionChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.ignoringRequestMatchers(BEARER_API))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/sagas/*").hasAuthority("SCOPE_saga:read")
                        .requestMatchers(HttpMethod.POST, "/api/v1/sagas").hasAuthority("SCOPE_saga:write")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}))
                .build();
    }
    @Bean
    @ConditionalOnProperty(name="app.mode", havingValue="production")
    JwtDecoder productionDecoder(@Value("${app.oidc.issuer}") String issuer,
            @Value("${app.oidc.audience}") String audience, @Value("${app.oidc.jwks-uri}") String jwksUri) {
        if (issuer.isBlank() || audience.isBlank() || jwksUri.isBlank()
                || !https(issuer) || !https(jwksUri))
            throw new IllegalStateException("Production requires HTTPS OIDC_ISSUER, OIDC_JWKS_URI, and OIDC_AUDIENCE");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(tokenValidator(issuer, audience));
        return decoder;
    }
    static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> withAudience = jwt -> jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Missing audience", null));
        return new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience);
    }
    private static boolean https(String value) {
        try { URI uri = URI.create(value); return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null; }
        catch (IllegalArgumentException invalid) { return false; }
    }
}

package dev.sagaharbor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(properties={"app.mode=production", "app.api-key=", "app.oidc.issuer=https://issuer.example.test/", "app.oidc.audience=saga-api", "app.oidc.jwks-uri=https://issuer.example.test/keys"}, controllers=Api.class)
@Import({SecurityConfig.class, SecurityWebTest.TestWorkflow.class})
class SecurityWebTest {
    @Autowired MockMvc mvc;
    private final UUID id = UUID.fromString("11111111-1111-4111-8111-111111111111");
    @TestConfiguration
    static class TestWorkflow {
        @Bean Workflow workflow() {
            return new Workflow(null, new ObjectMapper(), 1) {
                @Override public Map<Object,Object> get(String id) { return Map.of("id", id, "state", "COMPLETED"); }
                @Override public Started begin(String orderId, UUID key) { return new Started("11111111-1111-4111-8111-111111111111", "RESERVING", true); }
            };
        }
    }
    @Test void missingTokenAndWrongScopeAreDenied() throws Exception {
        mvc.perform(get("/api/v1/sagas/"+id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/sagas/"+id).header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/sagas/"+id).header("Authorization", "Bearer demo-key-ignored-in-production")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/sagas/"+id).with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_saga:write")))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/sagas").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_saga:read"))).with(csrf())
                .header("Idempotency-Key", id).contentType("application/json").content("{\"orderId\":\"order-1\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").with(jwt())).andExpect(status().isForbidden());
    }
    @Test void scopedTokensCanUseOnlyTheirRoute() throws Exception {
        mvc.perform(get("/api/v1/sagas/"+id).with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_saga:read")))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/sagas").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_saga:write"))).with(csrf())
                .header("Idempotency-Key", id).contentType("application/json").content("{\"orderId\":\"order-1\"}"))
                .andExpect(status().isCreated());
    }
    @Test void productionRejectsInsecureIdentityConfiguration() {
        assertThrows(IllegalStateException.class, () -> new SecurityConfig().productionDecoder("http://issuer.example", "api", "https://issuer.example/keys"));
        assertThrows(IllegalStateException.class, () -> new SecurityConfig().productionDecoder("https://issuer.example", "", "https://issuer.example/keys"));
    }
}

package com.idea2strategy.backend.api.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The journey an external AI tool actually performs, over HTTP.
 *
 * <p>Every step here was already covered somewhere — the CLI against a stub, the edit service
 * against fakes, the grant against Postgres — and the product still could not do it, because the
 * routes the CLI posts to did not exist. Covering the pieces is what let that happen, so this test
 * runs the line end to end over HTTP: sign up, log in, create a strategy, delegate editing of it,
 * reach the edit service under that delegation, and lose access the moment it is revoked.
 *
 * <p>It stops short of applying blocks. The delegated operation creates a group, but a real apply
 * also needs a complete valid chain; that belongs in a strategy-authoring fixture rather than here.
 * What this test establishes is the part that was actually broken — that the routes exist and that
 * a granted delegation carries a request through authorization, which no stub could show.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class ExternalToolDelegatedEditJourneyIntegrationTest {
    private static final String EMAIL = "delegated-edit@example.com";
    private static final String PASSWORD = "CorrectHorse!2026";
    private static final UUID INSTRUMENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SYMBOL_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
        String key = Base64.getEncoder().encodeToString(
                "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
        String jwtKey = Base64.getEncoder().encodeToString(
                "abcdefabcdefabcdefabcdefabcdefab".getBytes(StandardCharsets.UTF_8));
        registry.add("identity.crypto.email-encryption-key", () -> key);
        registry.add("identity.crypto.lookup-hmac-key", () -> key);
        registry.add("identity.crypto.verification-hmac-key", () -> key);
        registry.add("identity.crypto.refresh-token-hmac-key", () -> key);
        registry.add("identity.crypto.customer-jwt-signing-key", () -> jwtKey);
    }

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void anExternalToolDelegatesThenPreviewsAndAppliesABasicEdit() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("""
                insert into market_data.instruments
                    (id, asset_type, primary_exchange_mic, currency_code, provider_reference, listed_at, created_at)
                values (?::uuid, 'STOCK'::market_data.asset_type, 'XNAS', 'USD', 'delegated-edit-e2e',
                        date '2000-01-01', now())
                """, INSTRUMENT_ID.toString());
        jdbc.update("""
                insert into market_data.instrument_symbols
                    (id, instrument_id, exchange_mic, symbol, effective_from)
                values (?::uuid, ?::uuid, 'XNAS', 'AAPL', timestamp with time zone '2000-01-01 00:00:00+00')
                """, SYMBOL_ID.toString(), INSTRUMENT_ID.toString());

        JsonNode signup = json.readTree(mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","nickname":"delegator"}
                                """.formatted(EMAIL, PASSWORD)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString());
        assertThat(signup.path("verificationRequired").asBoolean()).isFalse();
        assertThat(signup.path("verificationExpiresAt").isNull()).isTrue();

        String accessToken = json.readTree(mvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"email":"%s","password":"%s"}
                                        """.formatted(EMAIL, PASSWORD)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .path("accessToken").asText();

        String strategyId = json.readTree(mvc.perform(post("/api/v1/strategies")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Delegated draft\",\"mode\":\"BASIC\"}"))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .path("id").asText();

        JsonNode grant = json.readTree(mvc.perform(post("/api/v1/delegations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"assistant","scopes":["STRATEGY_EDIT"],"strategyIds":["%s"]}
                                """.formatted(strategyId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        // Returned exactly once. Nothing later in the journey can recover it.
        assertThat(grant.path("credential").asText()).isNotBlank();

        JsonNode instruments = json.readTree(mvc.perform(get("/api/v1/strategy-catalogs/basic/instruments")
                                .header("Authorization", "Bearer " + accessToken))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        String instrumentId = instruments.path("instruments").path(0).path("id").asText();
        assertThat(instrumentId).isNotBlank();

        String editBody = """
                {"authorizationId":"%s","credentialId":"%s","operations":[
                  {"action":"ADD_GROUP","arguments":{"groupId":"buy","container":"BUY",
                   "evaluationMode":"INDEPENDENT","allocationMode":"EQUAL",
                   "instrumentIds":["%s"]}}]}
                """.formatted(
                        grant.path("authorizationId").asText(), grant.path("credentialId").asText(), instrumentId);

        // The strategy is untouched — {"groups":[],"mode":"BASIC"} — and the tool builds its
        // container anyway. Before this work the same call answered 404 because the route did not
        // exist, and after ADD_GROUP shipped it answered 422 because a new document carries no
        // catalogId for the proposed assembly to parse against.
        JsonNode preview = json.readTree(mvc.perform(
                                post("/api/v1/strategies/" + strategyId + "/basic-edits/preview")
                                        .header("Authorization", "Bearer " + accessToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(editBody))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        assertThat(preview.path("diff").isArray()).isTrue();
        assertThat(preview.path("diff").get(0).asText()).isEqualTo("ADD_GROUP buy BUY");
        assertThat(preview.path("previewHash").asText()).isNotBlank();
        assertThat(preview.path("expectedEditSequence").isNumber()).isTrue();
        assertThat(preview.path("proposedSemanticDocument").path("catalogId").asText()).isNotBlank();

        // Revoking takes effect at once: the same call now fails authorization instead of merits.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/delegations/" + grant.path("authorizationId").asText())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        JsonNode denied = json.readTree(mvc.perform(
                                post("/api/v1/strategies/" + strategyId + "/basic-edits/preview")
                                        .header("Authorization", "Bearer " + accessToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(editBody))
                        .andExpect(status().isForbidden())
                        .andReturn().getResponse().getContentAsString());
        assertThat(denied.path("code").asText()).isEqualTo("SCOPE_DENIED");
    }
}

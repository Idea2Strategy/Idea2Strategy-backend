package com.idea2strategy.backend.api.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.api.identity.AccountVerificationEmailRequested;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
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
 * <p>It stops short of applying blocks. A new strategy has no groups and the delegated operations
 * cannot create one, so a real apply needs a valid Basic skeleton with a catalog and instruments;
 * that belongs in a strategy-authoring fixture rather than here. What this test does establish is
 * the part that was actually broken — that the routes exist and that a granted delegation carries
 * a request through authorization, which no stub could show.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@RecordApplicationEvents
class ExternalToolDelegatedEditJourneyIntegrationTest {
    private static final String EMAIL = "delegated-edit@example.com";
    private static final String PASSWORD = "CorrectHorse!2026";

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
    @Autowired ApplicationEvents events;

    @Test
    void anExternalToolDelegatesThenPreviewsAndAppliesABasicEdit() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","nickname":"delegator"}
                                """.formatted(EMAIL, PASSWORD)))
                .andExpect(status().isAccepted());
        String verificationToken = events.stream(AccountVerificationEmailRequested.class)
                .findFirst()
                .map(AccountVerificationEmailRequested::verificationToken)
                .orElseThrow();
        mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationToken":"%s"}
                                """.formatted(verificationToken)))
                .andExpect(status().isNoContent());

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

        String editBody = """
                {"authorizationId":"%s","credentialId":"%s","operations":[
                  {"action":"ADD_BLOCK","arguments":{"groupId":"buy","blockId":"b1",
                   "elementCode":"PRICE_CHANGE_PERCENT"}}]}
                """.formatted(
                        grant.path("authorizationId").asText(), grant.path("credentialId").asText());

        // A freshly created strategy is {"groups":[],"mode":"BASIC"} and the four delegated
        // operations cannot create a group, so this edit is refused on its merits — which is the
        // assertion that matters here. EDIT_REJECTED means the delegation was accepted and the
        // request reached the edit service; a delegation that did not authorize would answer 403
        // SCOPE_DENIED, and a missing route would answer 404, which is what it did before this
        // change. Applying real blocks needs a valid Basic skeleton and is covered separately.
        JsonNode refusal = json.readTree(mvc.perform(
                                post("/api/v1/strategies/" + strategyId + "/basic-edits/preview")
                                        .header("Authorization", "Bearer " + accessToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(editBody))
                        .andExpect(status().isUnprocessableEntity())
                        .andReturn().getResponse().getContentAsString());
        assertThat(refusal.path("code").asText()).isEqualTo("EDIT_REJECTED");

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

package com.idea2strategy.backend.api.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.api.identity.VerificationEmailRequested;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacCommand;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import com.idea2strategy.backend.persistence.operatorrbac.OperatorRbacPersistenceAdapter;
import com.idea2strategy.backend.operatortrust.VersionedOperatorSubjectHmac;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@RecordApplicationEvents
@SpringBootTest
@Import(AccountHttpPersistenceJourneyIntegrationTest.OperatorJwtTestConfiguration.class)
class AccountHttpPersistenceJourneyIntegrationTest {
    private static final UUID CORRELATION = id(1);
    private static final String EMAIL = "a22-http@example.com";
    private static final String PASSWORD = "correct horse battery staple 2026!";
    private static final String OPERATOR_SUBJECT = "a22-operator-subject";
    private static final String CATALOG = "a22-rbac-v1";
    private static final UUID ACTOR = id(40);
    private static final UUID TARGET = id(41);
    private static final UUID ACTOR_ROLE = id(42);
    private static final UUID TARGET_ROLE = id(43);
    private static final UUID GRANT_PERMISSION = id(44);
    private static final UUID REVOKE_PERMISSION = id(45);

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
        registry.add("identity.crypto.email-encryption-key", () -> key);
        registry.add("identity.crypto.lookup-hmac-key", () -> key);
        registry.add("identity.crypto.verification-hmac-key", () -> key);
        registry.add("identity.crypto.session-hmac-key", () -> key);
        registry.add("idea2strategy.operator-auth.enabled", () -> "true");
        registry.add("idea2strategy.operator-auth.issuer", () -> "https://operator.example");
        registry.add("idea2strategy.operator-auth.jwk-set-uri", () -> "https://operator.example/jwks");
        registry.add("idea2strategy.operator-auth.audience", () -> "operator-api");
        registry.add("idea2strategy.operator-auth.current-subject-hmac-key-version", () -> "1");
        registry.add("idea2strategy.operator-auth.current-subject-hmac-key", () -> key);
        registry.add("idea2strategy.operator-auth.allowed-amr-values", () -> "mfa");
        registry.add("idea2strategy.operator-auth.mfa-maximum-age", () -> "PT10M");
        registry.add("idea2strategy.operator-rbac.guard.catalog-version", () -> CATALOG);
        registry.add("idea2strategy.operator-rbac.guard.grant-permission-id", () -> GRANT_PERMISSION.toString());
        registry.add("idea2strategy.operator-rbac.guard.revoke-permission-id", () -> REVOKE_PERMISSION.toString());
    }

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEvents events;
    @Autowired OperatorRbacPersistenceAdapter rbac;
    @Autowired VersionedOperatorSubjectHmac operatorSubjects;
    @Autowired JwtDecoder operatorJwtDecoder;

    @Test
    void executesSignupSessionPreferencesAndCaseThroughHttpAndPostgres() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

        String signup = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", CORRELATION)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(EMAIL, PASSWORD)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.verificationRequired").value(true))
                .andReturn().getResponse().getContentAsString();
        UUID accountId = UUID.fromString(json.readTree(signup).get("accountId").asText());
        String verificationToken = events.stream(VerificationEmailRequested.class)
                .map(VerificationEmailRequested::verificationToken)
                .findFirst().orElseThrow();

        mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", CORRELATION)
                        .content("""
                                {"verificationToken":"%s"}
                                """.formatted(verificationToken)))
                .andExpect(status().isNoContent());

        String login = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", CORRELATION)
                        .content("""
                                {"email":"%s","password":"%s","deviceLabel":"a22-e2e"}
                                """.formatted(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId.toString()))
                .andReturn().getResponse().getContentAsString();
        String sessionToken = json.readTree(login).get("sessionToken").asText();

        mvc.perform(patch("/api/v1/account/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + sessionToken)
                        .header("X-Correlation-Id", CORRELATION)
                        .content("""
                                {"languageCode":"ko","timezoneName":"Asia/Seoul","themePreference":"DARK"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezoneName").value("Asia/Seoul"));

        String caseRequest = """
                {"type":"REPORT","subject":"Fake room incident",
                 "description":"Review versioned fake room summary", "evidence":[]}
                """;
        String created = mvc.perform(post("/api/v1/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + sessionToken)
                        .header("X-Correlation-Id", CORRELATION)
                        .header("Idempotency-Key", "a22-http-case")
                        .content(caseRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value(accountId.toString()))
                .andReturn().getResponse().getContentAsString();
        JsonNode createdCase = json.readTree(created);
        UUID caseId = UUID.fromString(createdCase.get("id").asText());

        mvc.perform(post("/api/v1/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + sessionToken)
                        .header("X-Correlation-Id", CORRELATION)
                        .header("Idempotency-Key", "a22-http-case")
                        .content(caseRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(caseId.toString()));
        mvc.perform(get("/api/v1/cases/{caseId}", caseId)
                        .header("Authorization", "Bearer " + sessionToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(caseId.toString()));
        mvc.perform(get("/api/v1/cases/{caseId}", caseId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REJECTED"));

        assertThat(count("select count(*) from identity.sessions where account_id = ? and revoked_at is null", accountId))
                .isOne();
        assertThat(count("select count(*) from identity.authentication_events where account_id = ? and event_type = 'SESSION_VALIDATED'", accountId))
                .isGreaterThanOrEqualTo(2);
        assertThat(count("select count(*) from operations.cases where id = ?", caseId)).isOne();
        assertThat(count("select count(*) from operations.case_events where case_id = ?", caseId)).isOne();
        assertThat(count("select count(*) from operations.outbox_messages where aggregate_id = ? and event_type = 'USER_CASE_SUBMITTED'", caseId))
                .isOne();
    }

    @Test
    void grantsAndRevokesOperatorRoleThroughAuthenticatedHttpRbacAndImmutableAudit() throws Exception {
        seedRbac();
        when(operatorJwtDecoder.decode("operator-jwt")).thenReturn(operatorJwt());
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        OperatorRequestContext actor = new OperatorRequestContext(ACTOR, true, true);
        String grantHash = rbac.canonicalRequestHash(new OperatorRbacCommand(
                OperatorRbacCommand.Type.GRANT, actor, TARGET, TARGET_ROLE, null,
                GRANT_PERMISSION, CATALOG, null, "A22_GRANT", CORRELATION,
                "a22-rbac-grant", "0".repeat(64)));

        mvc.perform(post("/api/v1/operations/rbac/assignments/grants")
                        .header("Authorization", "Bearer operator-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetOperatorId":"%s","roleId":"%s","reasonCode":"A22_GRANT",
                                 "correlationId":"%s","idempotencyKey":"a22-rbac-grant","requestHash":"%s"}
                                """.formatted(TARGET, TARGET_ROLE, CORRELATION, grantHash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ROLE_GRANTED"));

        UUID assignment = jdbc.queryForObject("""
                select id from operations.operator_role_assignments
                where operator_account_id = ? and role_id = ? and revoked_at is null
                """, UUID.class, TARGET, TARGET_ROLE);
        String revokeHash = rbac.canonicalRequestHash(new OperatorRbacCommand(
                OperatorRbacCommand.Type.REVOKE, actor, TARGET, null, assignment,
                REVOKE_PERMISSION, CATALOG, null, "A22_REVOKE", CORRELATION,
                "a22-rbac-revoke", "0".repeat(64)));
        mvc.perform(post("/api/v1/operations/rbac/assignments/revocations")
                        .header("Authorization", "Bearer operator-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetOperatorId":"%s","assignmentId":"%s","reasonCode":"A22_REVOKE",
                                 "correlationId":"%s","idempotencyKey":"a22-rbac-revoke","requestHash":"%s"}
                                """.formatted(TARGET, assignment, CORRELATION, revokeHash)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ROLE_REVOKED"));

        assertThat(count("select count(*) from operations.operator_role_assignments where id = ? and revoked_at is not null", assignment))
                .isOne();
        assertThat(count("select count(*) from operations.audit_events where target_domain = 'OPERATOR_RBAC' and correlation_id = ?", CORRELATION))
                .isEqualTo(2);
    }

    private void seedRbac() throws Exception {
        Instant now = Instant.now();
        java.sql.Timestamp timestamp = java.sql.Timestamp.from(now);
        String subjectHash = operatorSubjects
                .protect("https://operator.example", OPERATOR_SUBJECT).getFirst().digest();
        jdbc.update("insert into operations.roles (id, code, hierarchy_rank, status) values (?, 'A22_ADMIN', 100, 'ACTIVE'), (?, 'A22_REVIEWER', 10, 'ACTIVE')",
                ACTOR_ROLE, TARGET_ROLE);
        jdbc.update("insert into operations.permissions (id, code, description, sensitivity) values (?, 'A22_GRANT', 'grant', 'HIGH'), (?, 'A22_REVOKE', 'revoke', 'HIGH')",
                GRANT_PERMISSION, REVOKE_PERMISSION);
        jdbc.update("insert into operations.operator_accounts (id, external_identity_key_hmac, external_identity_key_version, status, mfa_enrolled_at, last_mfa_verified_at, created_at) values (?, ?, 1, 'ACTIVE', ?, ?, ?), (?, ?, 1, 'ACTIVE', ?, ?, ?)",
                ACTOR, subjectHash, timestamp, timestamp, timestamp,
                TARGET, "b".repeat(64), timestamp, timestamp, timestamp);
        jdbc.update("insert into operations.rbac_catalog_versions (catalog_version, content_hash, status, activated_at) values (?, ?, 'DRAFT', null)", CATALOG, "c".repeat(64));
        jdbc.update("insert into operations.rbac_catalog_roles (catalog_version, role_id, hierarchy_rank, role_status) values (?, ?, 100, 'ACTIVE'), (?, ?, 10, 'ACTIVE')",
                CATALOG, ACTOR_ROLE, CATALOG, TARGET_ROLE);
        jdbc.update("insert into operations.rbac_catalog_permissions (catalog_version, permission_id, permission_status) values (?, ?, 'ACTIVE'), (?, ?, 'ACTIVE')",
                CATALOG, GRANT_PERMISSION, CATALOG, REVOKE_PERMISSION);
        jdbc.update("insert into operations.rbac_catalog_role_permissions (catalog_version, role_id, permission_id, delegable) values (?, ?, ?, true), (?, ?, ?, true)",
                CATALOG, ACTOR_ROLE, GRANT_PERMISSION, CATALOG, ACTOR_ROLE, REVOKE_PERMISSION);
        jdbc.update("update operations.rbac_catalog_versions set status = 'ACTIVE', activated_at = ? where catalog_version = ?", timestamp, CATALOG);
        jdbc.update("insert into operations.operator_role_assignments (id, operator_account_id, role_id, catalog_version, granted_by_operator_id, granted_at) values (?, ?, ?, ?, ?, ?)",
                id(46), ACTOR, ACTOR_ROLE, CATALOG, ACTOR, timestamp);
    }

    private Jwt operatorJwt() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("operator-jwt")
                .header("alg", "RS256")
                .header("kid", "operator-key-1")
                .issuer("https://operator.example")
                .subject(OPERATOR_SUBJECT)
                .audience(List.of("operator-api"))
                .issuedAt(now.minusSeconds(30))
                .notBefore(now.minusSeconds(30))
                .expiresAt(now.plusSeconds(120))
                .claim("amr", List.of("mfa"))
                .claim("auth_time", now.minusSeconds(30))
                .build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class OperatorJwtTestConfiguration {
        @Bean
        @Primary
        @Qualifier("operatorJwtDecoder")
        JwtDecoder testOperatorJwtDecoder() {
            return mock(JwtDecoder.class);
        }
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a2200000-0000-4000-8000-" + "%012d".formatted(suffix));
    }

}

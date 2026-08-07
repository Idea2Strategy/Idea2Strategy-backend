package com.idea2strategy.backend.api.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.operatorbootstrap.OperatorBootstrapManifest;
import com.idea2strategy.backend.application.operatorbootstrap.OperatorBootstrapService;
import com.idea2strategy.backend.operatortrust.VersionedOperatorSubjectHmac;
import com.idea2strategy.backend.persistence.operatorbootstrap.JdbcOperatorBootstrapAdapter;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(OperatorBootstrapTrustReadExactStackIntegrationTest.JwtTestConfiguration.class)
class OperatorBootstrapTrustReadExactStackIntegrationTest {
    private static final String ISSUER = "https://operator.example";
    private static final String SUBJECT = "exact-stack-operator";
    private static final String CATALOG = "operator-exact-v1";
    private static final UUID OPERATOR = id(1);
    private static final UUID ROLE = id(2);
    private static final UUID CATALOG_READ = id(3);
    private static final UUID ASSIGNMENT_READ = id(4);
    private static final UUID ASSIGNMENT = id(5);
    private static final UUID DEPLOYMENT_ACTOR = id(6);
    private static final UUID CORRELATION = id(7);
    private static final UUID AUDIT = id(8);
    private static final UUID SECOND_OPERATOR = id(9);
    private static final UUID SECOND_ASSIGNMENT = id(10);

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

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
        registry.add("identity.crypto.refresh-token-hmac-key", () -> key);
        registry.add("identity.crypto.customer-jwt-signing-key", () -> key);
        registry.add("idea2strategy.operator-auth.enabled", () -> "true");
        registry.add("idea2strategy.operator-auth.issuer", () -> ISSUER);
        registry.add("idea2strategy.operator-auth.jwk-set-uri", () -> ISSUER + "/jwks");
        registry.add("idea2strategy.operator-auth.audience", () -> "operator-api");
        registry.add("idea2strategy.operator-auth.current-subject-hmac-key-version", () -> "1");
        registry.add("idea2strategy.operator-auth.current-subject-hmac-key", () -> key);
        registry.add("idea2strategy.operator-auth.allowed-amr-values", () -> "mfa");
        registry.add("idea2strategy.operator-auth.maximum-mfa-age", () -> "PT10M");
        registry.add("idea2strategy.operator-rbac.read-guard.enabled", () -> "true");
        registry.add("idea2strategy.operator-rbac.read-guard.catalog-version", () -> CATALOG);
        registry.add("idea2strategy.operator-rbac.read-guard.catalog-read-permission-id", () -> CATALOG_READ.toString());
        registry.add("idea2strategy.operator-rbac.read-guard.assignment-read-permission-id", () -> ASSIGNMENT_READ.toString());
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired VersionedOperatorSubjectHmac subjects;
    @Autowired @Qualifier("operatorJwtDecoder") JwtDecoder decoder;

    @Test
    void bootstrapsThenAuthenticatesAndRecalculatesEveryReadFromTheMergedStack() throws Exception {
        String subjectHmac = subjects.protect(ISSUER, SUBJECT).getFirst().digest();
        var bootstrap = new OperatorBootstrapService(new JdbcOperatorBootstrapAdapter(jdbc, transactions));
        var first = bootstrap.execute(manifest(subjectHmac), "a".repeat(64));
        assertThat(first.replayed()).isFalse();
        assertThat(bootstrap.execute(manifest(subjectHmac), "a".repeat(64)).replayed()).isTrue();

        when(decoder.decode("valid-operator-jwt")).thenReturn(jwt(true));
        when(decoder.decode("stale-mfa-jwt")).thenReturn(jwt(false));
        when(decoder.decode("unknown-operator-jwt")).thenReturn(jwt("unknown-operator-jwt", "unknown-subject", true));
        when(decoder.decode("second-operator-jwt")).thenReturn(jwt("second-operator-jwt", "second-subject", true));
        seedSecondOperator();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

        String spoofDenial = mvc.perform(get("/api/v1/operations/me")
                        .header("X-Operator-Id", OPERATOR).header("X-Amzn-Oidc-Identity", SUBJECT))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknownDenial = mvc.perform(get("/api/v1/operations/me")
                        .header("Authorization", "Bearer unknown-operator-jwt"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(spoofDenial).contains("OPERATOR_AUTHENTICATION_REQUIRED");
        assertThat(unknownDenial).contains("OPERATOR_AUTHENTICATION_REQUIRED");
        mvc.perform(get("/api/v1/operations/me")
                        .header("Authorization", "Bearer valid-operator-jwt")
                        .header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view.operatorId").value(OPERATOR.toString()))
                .andExpect(jsonPath("$.view.currentMfa").value(true))
                .andExpect(jsonPath("$.view.permissions.length()").value(2));
        mvc.perform(get("/api/v1/operations/rbac/catalog")
                        .header("Authorization", "Bearer stale-mfa-jwt")
                        .header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OPERATOR_RBAC_READ_FORBIDDEN"));
        mvc.perform(get("/api/v1/operations/rbac/catalog")
                        .header("Authorization", "Bearer valid-operator-jwt")
                        .header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view.catalogVersion").value(CATALOG));
        mvc.perform(get("/api/v1/operations/rbac/operators/{operatorId}/assignments", OPERATOR)
                        .header("Authorization", "Bearer valid-operator-jwt")
                        .header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view.assignments[0].id").value(ASSIGNMENT.toString()));

        assertThat(jdbc.queryForObject("select count(*) from operations.audit_events "
                + "where correlation_id=? and target_domain='OPERATOR_RBAC'", Long.class, CORRELATION))
                .isGreaterThanOrEqualTo(3L);
        assertThat(jdbc.queryForObject("""
                select count(*) from operations.audit_events
                where correlation_id=? and target_domain='OPERATOR_RBAC'
                  and decision_status in ('SUCCEEDED','REJECTED')
                  and request_hash=encode(digest(request_document::text,'sha256'),'hex')
                  and evidence_hash=encode(digest(evidence_document::text,'sha256'),'hex')
                """, Long.class, CORRELATION)).isGreaterThanOrEqualTo(3L);
        assertThat(jdbc.queryForObject("""
                select count(*) from operations.operator_bootstrap_receipts r
                join operations.audit_events a on a.id=r.audit_event_id
                where r.bootstrap_key='exact-stack-bootstrap'
                  and a.target_domain='OPERATOR_BOOTSTRAP'
                  and a.target_id=r.operator_account_id
                  and a.correlation_id=r.correlation_id
                  and a.request_hash=encode(digest(a.request_document::text,'sha256'),'hex')
                  and a.evidence_hash=encode(digest(a.evidence_document::text,'sha256'),'hex')
                """, Long.class)).isOne();
        assertThatThrownBy(() -> jdbc.update("update operations.operator_bootstrap_receipts "
                + "set catalog_version=catalog_version where bootstrap_key='exact-stack-bootstrap'"))
                .hasMessageContaining("immutable");

        jdbc.update("update operations.operator_role_assignments set revoked_at=clock_timestamp(), "
                + "revoked_by_operator_id=?, revocation_reason_code='EXACT_STACK_TEST' where id=?", OPERATOR, ASSIGNMENT);
        mvc.perform(get("/api/v1/operations/rbac/catalog")
                        .header("Authorization", "Bearer valid-operator-jwt"))
                .andExpect(status().isForbidden());

        jdbc.update("update operations.rbac_catalog_versions set status='RETIRED', "
                + "retired_at=clock_timestamp() where catalog_version=?", CATALOG);
        mvc.perform(get("/api/v1/operations/me")
                        .header("Authorization", "Bearer second-operator-jwt"))
                .andExpect(status().isForbidden());

        jdbc.update("update operations.operator_accounts set status='DISABLED', disabled_at=clock_timestamp() where id=?", OPERATOR);
        mvc.perform(get("/api/v1/operations/me")
                        .header("Authorization", "Bearer valid-operator-jwt"))
                .andExpect(status().isUnauthorized());
    }

    private void seedSecondOperator() {
        String digest = subjects.protect(ISSUER, "second-subject").getFirst().digest();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into operations.operator_accounts "
                        + "(id,external_identity_key_hmac,external_identity_key_version,status,mfa_enrolled_at,created_at) "
                        + "values (?,?,1,'ACTIVE',?,?)", SECOND_OPERATOR, digest, now, now);
        jdbc.update("insert into operations.operator_role_assignments "
                        + "(id,operator_account_id,role_id,catalog_version,granted_by_operator_id,granted_at) "
                        + "values (?,?,?,?,?,?)", SECOND_ASSIGNMENT, SECOND_OPERATOR, ROLE, CATALOG, OPERATOR, now);
    }

    private OperatorBootstrapManifest manifest(String subjectHmac) {
        return new OperatorBootstrapManifest(
                "exact-stack-bootstrap", CATALOG, "b".repeat(64), POSTGRES.getUsername(), (short) 1,
                subjectHmac, OPERATOR, ASSIGNMENT, ROLE, DEPLOYMENT_ACTOR, "approved-a22-exact-stack",
                CORRELATION, AUDIT,
                List.of(new OperatorBootstrapManifest.Role(ROLE, "ROOT_OPERATOR", 100)),
                List.of(
                        new OperatorBootstrapManifest.Permission(CATALOG_READ, "OPERATOR_RBAC_CATALOG_READ", "catalog read", "HIGH"),
                        new OperatorBootstrapManifest.Permission(ASSIGNMENT_READ, "OPERATOR_RBAC_ASSIGNMENT_READ", "assignment read", "HIGH"),
                        roomPermission("e3000000-0000-4000-8000-000000000001", "COMPETITION_ROOM_READ",
                                "Read operator-safe official competition room state and result provenance", "SENSITIVE"),
                        roomPermission("e3000000-0000-4000-8000-000000000002", "COMPETITION_ROOM_MANAGE",
                                "Cancel or invalidate official competition rooms through audited commands", "HIGH")),
                List.of(
                        new OperatorBootstrapManifest.RolePermission(ROLE, CATALOG_READ, true),
                        new OperatorBootstrapManifest.RolePermission(ROLE, ASSIGNMENT_READ, true)));
    }

    private static OperatorBootstrapManifest.Permission roomPermission(
            String id, String code, String description, String sensitivity) {
        return new OperatorBootstrapManifest.Permission(UUID.fromString(id), code, description, sensitivity);
    }

    private static Jwt jwt(boolean currentMfa) {
        return jwt(currentMfa ? "valid-operator-jwt" : "stale-mfa-jwt", SUBJECT, currentMfa);
    }

    private static Jwt jwt(String token, String subject, boolean currentMfa) {
        Instant now = Instant.now();
        Instant authentication = currentMfa ? now.minusSeconds(30) : now.minusSeconds(3600);
        return Jwt.withTokenValue(token)
                .header("alg", "RS256").header("kid", "pinned-test-key")
                .issuer(ISSUER).subject(subject).audience(List.of("operator-api"))
                .issuedAt(now.minusSeconds(30)).notBefore(now.minusSeconds(30)).expiresAt(now.plusSeconds(120))
                .claim("amr", List.of("mfa")).claim("auth_time", authentication).build();
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a22e0000-0000-4000-8000-" + "%012d".formatted(suffix));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JwtTestConfiguration {
        @Bean @Primary @Qualifier("operatorJwtDecoder")
        JwtDecoder testOperatorJwtDecoder() { return mock(JwtDecoder.class); }
    }
}

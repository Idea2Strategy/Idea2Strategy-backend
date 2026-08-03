package com.idea2strategy.backend.persistence.operatorrbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacCommand;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacCommandService;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacIdempotencyConflictException;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacResult;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = OperatorRbacPersistenceIntegrationTest.TestApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OperatorRbacPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-03T01:00:00Z");
    private static final String VERSION = "review-catalog-v1";
    private static final UUID HIGH_ROLE = id(11);
    private static final UUID LOW_ROLE = id(12);
    private static final UUID COMMAND_PERMISSION = id(21);
    private static final UUID LOW_PERMISSION = id(22);

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired OperatorRbacPersistenceAdapter adapter;

    @Test
    void failsClosedWithoutCatalogThenSerializesGrantReplayConflictAndRevokeWithEvidence() throws Exception {
        UUID actor = operator("actor", true, true);
        UUID target = operator("target", true, true);
        OperatorRbacCommandService service = service();

        OperatorRbacCommand unavailable = grant(actor, target, "unavailable", LOW_ROLE);
        assertThat(service.execute(unavailable).code()).isEqualTo("RBAC_CATALOG_UNAVAILABLE");
        assertThat(text("select resolved_rbac_catalog_version from operations.audit_events where idempotency_key = ?",
                unavailable.idempotencyKey())).isNull();

        seedCatalog();
        assertThatThrownBy(() -> jdbc.update("""
                insert into operations.rbac_catalog_role_permissions
                    (catalog_version, role_id, permission_id, delegable)
                values (?, ?, ?, true)
                """, VERSION, LOW_ROLE, COMMAND_PERMISSION))
                .hasMessageContaining("immutable");
        assign(actor, HIGH_ROLE, actor, NOW.minusSeconds(60));
        OperatorRbacCommand grant = grant(actor, target, "grant-once", LOW_ROLE);
        OperatorRbacResult first;
        OperatorRbacResult replay;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> service.execute(grant));
            var b = executor.submit(() -> service.execute(grant));
            first = a.get(10, TimeUnit.SECONDS);
            replay = b.get(10, TimeUnit.SECONDS);
        }
        assertThat(first).isEqualTo(replay);
        assertThat(first.decisionStatus()).isEqualTo(OperatorRbacResult.DecisionStatus.APPLIED);
        assertThat(count("""
                select count(*) from operations.operator_role_assignments
                where operator_account_id = ? and role_id = ? and revoked_at is null
                """, target, LOW_ROLE)).isEqualTo(1);
        assertThat(count("select count(*) from operations.audit_events where idempotency_key = ?", "grant-once"))
                .isEqualTo(1);
        assertThatThrownBy(() -> service.execute(withHash(grant, "f".repeat(64))))
                .isInstanceOf(OperatorRbacIdempotencyConflictException.class);

        UUID assignment = jdbc.queryForObject("""
                select id from operations.operator_role_assignments
                where operator_account_id = ? and role_id = ? and revoked_at is null
                """, UUID.class, target, LOW_ROLE);
        OperatorRbacCommand revoke = revoke(actor, target, assignment, "revoke-once");
        assertThat(service.execute(revoke).code()).isEqualTo("ROLE_REVOKED");
        assertThat(jdbc.queryForObject("select revoked_at is not null from operations.operator_role_assignments where id = ?",
                Boolean.class, assignment)).isTrue();
        assertThat(text("select decision_status from operations.audit_events where idempotency_key = ?", "revoke-once"))
                .isEqualTo("SUCCEEDED");
        assertThatThrownBy(() -> jdbc.update(
                "update operations.audit_events set response_code = 'CHANGED' where idempotency_key = ?", "revoke-once"))
                .hasMessageContaining("immutable");
    }

    private OperatorRbacCommandService service() {
        return new OperatorRbacCommandService(adapter, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private OperatorRbacCommand grant(UUID actor, UUID target, String key, UUID role) {
        OperatorRbacCommand draft = new OperatorRbacCommand(
                OperatorRbacCommand.Type.GRANT, new OperatorRequestContext(actor, true, true), target,
                role, null, COMMAND_PERMISSION, VERSION, NOW.plusSeconds(3600), "REVIEW_EXAMPLE",
                UUID.randomUUID(), key, "0".repeat(64));
        return withHash(draft, adapter.canonicalRequestHash(draft));
    }

    private OperatorRbacCommand revoke(UUID actor, UUID target, UUID assignment, String key) {
        OperatorRbacCommand draft = new OperatorRbacCommand(
                OperatorRbacCommand.Type.REVOKE, new OperatorRequestContext(actor, true, true), target,
                null, assignment, COMMAND_PERMISSION, VERSION, null, "REVIEW_EXAMPLE",
                UUID.randomUUID(), key, "0".repeat(64));
        return withHash(draft, adapter.canonicalRequestHash(draft));
    }

    private OperatorRbacCommand withHash(OperatorRbacCommand c, String hash) {
        return new OperatorRbacCommand(c.type(), c.requestContext(), c.targetOperatorId(), c.roleId(),
                c.assignmentId(), c.requiredPermissionId(), c.expectedCatalogVersion(), c.expiresAt(),
                c.reasonCode(), c.correlationId(), c.idempotencyKey(), hash);
    }

    private void seedCatalog() {
        jdbc.update("insert into operations.roles (id, code, hierarchy_rank, status) values (?, 'REVIEW_HIGH', 100, 'ACTIVE'), (?, 'REVIEW_LOW', 10, 'ACTIVE')",
                HIGH_ROLE, LOW_ROLE);
        jdbc.update("""
                insert into operations.permissions (id, code, description, sensitivity)
                values (?, 'REVIEW_COMMAND', 'test fixture', 'HIGH'),
                       (?, 'REVIEW_LOW_PERMISSION', 'test fixture', 'LOW')
                """, COMMAND_PERMISSION, LOW_PERMISSION);
        jdbc.update("""
                insert into operations.rbac_catalog_versions
                    (catalog_version, content_hash, status)
                values (?, ?, 'DRAFT')
                """, VERSION, "a".repeat(64));
        jdbc.update("""
                insert into operations.rbac_catalog_roles
                    (catalog_version, role_id, hierarchy_rank, role_status)
                values (?, ?, 100, 'ACTIVE'), (?, ?, 10, 'ACTIVE')
                """, VERSION, HIGH_ROLE, VERSION, LOW_ROLE);
        jdbc.update("""
                insert into operations.rbac_catalog_permissions
                    (catalog_version, permission_id, permission_status)
                values (?, ?, 'ACTIVE'), (?, ?, 'ACTIVE')
                """, VERSION, COMMAND_PERMISSION, VERSION, LOW_PERMISSION);
        jdbc.update("""
                insert into operations.rbac_catalog_role_permissions
                    (catalog_version, role_id, permission_id, delegable)
                values (?, ?, ?, true), (?, ?, ?, true), (?, ?, ?, false)
                """, VERSION, HIGH_ROLE, COMMAND_PERMISSION, VERSION, HIGH_ROLE, LOW_PERMISSION,
                VERSION, LOW_ROLE, LOW_PERMISSION);
        jdbc.update("""
                update operations.rbac_catalog_versions
                set status = 'ACTIVE', activated_at = clock_timestamp()
                where catalog_version = ?
                """, VERSION);
    }

    private void assign(UUID operator, UUID role, UUID grantor, Instant grantedAt) {
        jdbc.update("""
                insert into operations.operator_role_assignments
                    (operator_account_id, role_id, catalog_version, granted_by_operator_id, granted_at)
                values (?, ?, ?, ?, ?)
                """, operator, role, VERSION, grantor, Timestamp.from(grantedAt));
    }

    private UUID operator(String key, boolean active, boolean mfa) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into operations.operator_accounts
                    (id, external_identity_key_hmac, external_identity_key_version,
                     status, mfa_enrolled_at, created_at)
                values (?, ?, 1, ?, ?, clock_timestamp())
                """, id, key + id, active ? "ACTIVE" : "DISABLED",
                mfa ? Timestamp.from(NOW.minusSeconds(60)) : null);
        return id;
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private String text(String sql, Object... args) { return jdbc.queryForObject(sql, String.class, args); }

    private static UUID id(int suffix) {
        return UUID.fromString("a1300000-0000-4000-8000-" + "%012d".formatted(suffix));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(OperatorRbacPersistenceAdapter.class)
    static class TestApplication {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}

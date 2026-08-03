package com.idea2strategy.backend.persistence.operatorbootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.operatorbootstrap.OperatorBootstrapManifest;
import com.idea2strategy.backend.application.operatorbootstrap.OperatorBootstrapRejectedException;
import com.idea2strategy.backend.application.operatorbootstrap.OperatorBootstrapService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = JdbcOperatorBootstrapAdapterIntegrationTest.TestApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JdbcOperatorBootstrapAdapterIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test
    @Order(3)
    void appliesAtomicallyReplaysExactlyAndRejectsConflictsAndNonEmptyState() {
        var service = new OperatorBootstrapService(new JdbcOperatorBootstrapAdapter(jdbc, transactions));
        var manifest = manifest("bootstrap-1", id(90));
        var first = service.execute(manifest, "a".repeat(64));
        assertThat(first.replayed()).isFalse();
        assertThat(service.execute(manifest, "a".repeat(64)).replayed()).isTrue();
        assertThatThrownBy(() -> service.execute(manifest, "b".repeat(64)))
                .isInstanceOf(OperatorBootstrapRejectedException.class)
                .hasMessage("OPERATOR_BOOTSTRAP_CONFLICTING_REPLAY");
        assertThat(jdbc.queryForObject("select count(*) from operations.operator_bootstrap_receipts", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select audit_event_id from operations.operator_bootstrap_receipts "
                + "where bootstrap_key = 'bootstrap-1'", UUID.class)).isEqualTo(id(90));
        assertThat(jdbc.queryForObject("select actor_id from operations.audit_events where id = ?",
                UUID.class, id(90))).isEqualTo(id(5));
        assertThat(jdbc.queryForObject("select evidence_document ->> 'databaseRole' from operations.audit_events "
                + "where id = ?", String.class, id(90))).isEqualTo(POSTGRES.getUsername());
        assertThat(jdbc.queryForObject("select evidence_document ->> 'grantProvenance' from operations.audit_events "
                + "where id = ?", String.class, id(90))).isEqualTo("approved-change-42");
        assertThat(jdbc.queryForObject("select (a.created_at = r.applied_at and a.mfa_enrolled_at = r.applied_at "
                        + "and x.granted_at = r.applied_at and c.activated_at = r.applied_at "
                        + "and e.occurred_at = r.applied_at) from operations.operator_bootstrap_receipts r "
                        + "join operations.operator_accounts a on a.id=r.operator_account_id "
                        + "join operations.operator_role_assignments x on x.id=r.operator_role_assignment_id "
                        + "join operations.rbac_catalog_versions c on c.catalog_version=r.catalog_version "
                        + "join operations.audit_events e on e.id=r.audit_event_id where r.bootstrap_key='bootstrap-1'",
                Boolean.class)).isTrue();
        assertThatThrownBy(() -> service.execute(manifest("bootstrap-2", id(92)), "f".repeat(64)))
                .isInstanceOf(OperatorBootstrapRejectedException.class)
                .hasMessage("OPERATOR_BOOTSTRAP_STATE_NOT_EMPTY");
        assertThatThrownBy(() -> jdbc.update("update operations.operator_bootstrap_receipts "
                + "set catalog_version = catalog_version where bootstrap_key = 'bootstrap-1'"))
                .hasMessageContaining("immutable");
    }

    @Test
    @Order(1)
    void databaseFailureRollsBackEveryBootstrapWrite() {
        UUID occupiedAuditId = id(91);
        jdbc.update("insert into operations.audit_events "
                        + "(id, actor_type, actor_id, action_type, target_domain, target_id, reason_code, "
                        + "correlation_id, idempotency_key, occurred_at) values (?, 'DEPLOYMENT', ?, "
                        + "'PREEXISTING', 'TEST', ?, 'TEST', ?, 'preexisting-audit', ?)",
                occupiedAuditId, id(70), id(71), id(72),
                java.sql.Timestamp.from(Instant.parse("2026-08-03T00:00:00Z")));
        var service = new OperatorBootstrapService(new JdbcOperatorBootstrapAdapter(jdbc, transactions));
        assertThatThrownBy(() -> service.execute(manifest("bootstrap-fail", occupiedAuditId), "c".repeat(64)))
                .isInstanceOf(OperatorBootstrapRejectedException.class)
                .hasMessage("OPERATOR_BOOTSTRAP_TRANSACTION_FAILED");
        assertThat(jdbc.queryForObject("select count(*) from operations.rbac_catalog_versions", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from operations.operator_accounts", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from operations.operator_bootstrap_receipts", Long.class))
                .isZero();
    }

    @Test
    @Order(2)
    void rejectsUnexpectedDatabaseRoleWithoutWrites() {
        var service = new OperatorBootstrapService(new JdbcOperatorBootstrapAdapter(jdbc, transactions));
        var wrongRole = manifest("wrong-role", id(93), "not_" + POSTGRES.getUsername());
        assertThatThrownBy(() -> service.execute(wrongRole, "9".repeat(64)))
                .isInstanceOf(OperatorBootstrapRejectedException.class)
                .hasMessage("OPERATOR_BOOTSTRAP_DATABASE_ROLE_MISMATCH");
        assertThat(jdbc.queryForObject("select count(*) from operations.operator_bootstrap_receipts", Long.class))
                .isZero();
    }

    private static OperatorBootstrapManifest manifest(String key, UUID auditId) {
        return manifest(key, auditId, POSTGRES.getUsername());
    }

    private static OperatorBootstrapManifest manifest(String key, UUID auditId, String databaseRole) {
        UUID role = id(1);
        UUID permission = id(2);
        return new OperatorBootstrapManifest(key, "catalog-v1", "d".repeat(64), databaseRole, (short) 1,
                "e".repeat(64), id(3), id(4), role, id(5), "approved-change-42", id(6), auditId,
                List.of(new OperatorBootstrapManifest.Role(role, "ROOT_OPERATOR", 100)),
                List.of(new OperatorBootstrapManifest.Permission(permission, "OPERATOR_RBAC_CATALOG_READ",
                                "Read the operator RBAC catalog", "HIGH"),
                        new OperatorBootstrapManifest.Permission(
                                UUID.fromString("e3000000-0000-4000-8000-000000000001"),
                                "COMPETITION_ROOM_READ",
                                "Read operator-safe official competition room state and result provenance",
                                "SENSITIVE"),
                        new OperatorBootstrapManifest.Permission(
                                UUID.fromString("e3000000-0000-4000-8000-000000000002"),
                                "COMPETITION_ROOM_MANAGE",
                                "Cancel or invalidate official competition rooms through audited commands",
                                "HIGH")),
                List.of(new OperatorBootstrapManifest.RolePermission(role, permission, true)));
    }

    private static UUID id(long value) { return new UUID(0, value); }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}
}

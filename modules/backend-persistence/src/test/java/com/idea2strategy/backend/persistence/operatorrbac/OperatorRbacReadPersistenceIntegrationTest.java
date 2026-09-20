package com.idea2strategy.backend.persistence.operatorrbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacReadModels;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = OperatorRbacReadPersistenceIntegrationTest.TestApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OperatorRbacReadPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-03T07:30:00Z");
    private static final String CURRENT = "operator-read-v2";
    private static final String OLD = "operator-read-v1";
    private static final UUID OPERATOR = id(1);
    private static final UUID ROLE = id(2);
    private static final UUID PERMISSION = id(3);

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
    @Autowired OperatorRbacReadPersistenceAdapter adapter;

    @Test
    void readsFreshEffectiveSelfCatalogAndCompleteAssignmentHistory() {
        seed();

        var actor = adapter.loadActorState(OPERATOR, NOW);
        assertThat(actor.active()).isTrue();
        assertThat(actor.activeCatalogVersion()).isEqualTo(CURRENT);
        assertThat(actor.effectivePermissionIds()).containsExactly(PERMISSION);
        assertThat(actor.self().roles()).extracting(OperatorRbacReadModels.RoleView::code)
                .containsExactly("A22_READER");
        assertThat(actor.self().permissions()).extracting(OperatorRbacReadModels.PermissionView::code)
                .containsExactly("A22_ASSIGNMENT_READ");
        assertThat(actor.self().assignments()).hasSize(1)
                .allSatisfy(value -> assertThat(value.status())
                        .isEqualTo(OperatorRbacReadModels.AssignmentStatus.ACTIVE));

        var catalog = adapter.loadCatalog(CURRENT, NOW).orElseThrow();
        assertThat(catalog.roles()).hasSize(1);
        assertThat(catalog.permissions()).hasSize(1);
        assertThat(catalog.rolePermissions()).singleElement()
                .satisfies(mapping -> assertThat(mapping.delegable()).isTrue());
        assertThat(adapter.loadCatalog(OLD, NOW)).isEmpty();

        var history = adapter.loadAssignments(OPERATOR, CURRENT, NOW).orElseThrow();
        assertThat(history.assignments()).extracting(OperatorRbacReadModels.AssignmentView::status)
                .containsExactlyInAnyOrder(
                        OperatorRbacReadModels.AssignmentStatus.ACTIVE,
                        OperatorRbacReadModels.AssignmentStatus.FUTURE,
                        OperatorRbacReadModels.AssignmentStatus.EXPIRED,
                        OperatorRbacReadModels.AssignmentStatus.REVOKED,
                        OperatorRbacReadModels.AssignmentStatus.STALE_CATALOG);
        assertThat(adapter.loadAssignments(id(99), CURRENT, NOW)).isEmpty();
    }

    @Test
    void recordsImmutableReadSuccessAndAuthorizedDenialWithoutProviderIdentity() {
        seed();
        UUID success = id(80);
        UUID denied = id(81);
        adapter.recordDecision(new OperatorRbacReadModels.AuditDecision(
                OperatorRbacReadModels.Kind.SELF, OPERATOR, OPERATOR, success,
                null, CURRENT, OperatorRbacReadModels.DecisionStatus.SUCCEEDED,
                "OPERATOR_SELF_READ", NOW, null, java.util.Set.of(PERMISSION), false, true));
        adapter.recordDecision(new OperatorRbacReadModels.AuditDecision(
                OperatorRbacReadModels.Kind.ASSIGNMENTS, OPERATOR, id(99), denied,
                CURRENT, CURRENT, OperatorRbacReadModels.DecisionStatus.REJECTED,
                "OPERATOR_ASSIGNMENTS_NOT_FOUND", NOW, PERMISSION,
                java.util.Set.of(PERMISSION), true, true));
        adapter.recordDecision(new OperatorRbacReadModels.AuditDecision(
                OperatorRbacReadModels.Kind.CATALOG, OPERATOR, OPERATOR, denied,
                "stale-reviewed-version", CURRENT, OperatorRbacReadModels.DecisionStatus.REJECTED,
                "OPERATOR_RBAC_CATALOG_VERSION_CONFLICT", NOW, PERMISSION,
                java.util.Set.of(PERMISSION), true, true));

        assertThat(jdbc.queryForObject("""
                select count(*) from operations.audit_events
                where correlation_id in (?, ?) and target_domain = 'OPERATOR_RBAC'
                  and jsonb_exists(request_document, 'kind')
                  and not (jsonb_exists(request_document, 'subject')
                           or jsonb_exists(request_document, 'token'))
                """, Integer.class, success, denied)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                select response_status from operations.audit_events
                where correlation_id = ? and response_code = 'OPERATOR_ASSIGNMENTS_NOT_FOUND'
                """, Integer.class, denied)).isEqualTo(404);
        assertThat(jdbc.queryForObject("""
                select count(*) from operations.audit_events
                where correlation_id = ? and resolved_rbac_catalog_version is null
                  and response_status = 409
                """, Integer.class, denied)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                update operations.audit_events set response_code = 'CHANGED'
                where correlation_id = ?
                """, success)).hasMessageContaining("immutable");
    }

    private void seed() {
        Integer existing = jdbc.queryForObject(
                "select count(*) from operations.operator_accounts where id = ?",
                Integer.class, OPERATOR);
        if (existing != null && existing == 1) return;
        jdbc.update("""
                insert into operations.operator_accounts (id, status, created_at)
                values (?, 'ACTIVE', ?)
                """, OPERATOR, timestamp(NOW.minusSeconds(3600)));
        jdbc.update("""
                insert into operations.roles (id, code, hierarchy_rank, status)
                values (?, 'A22_READER', 10, 'ACTIVE')
                """, ROLE);
        jdbc.update("""
                insert into operations.permissions (id, code, description, sensitivity)
                values (?, 'A22_ASSIGNMENT_READ', 'read assignment history', 'LOW')
                """, PERMISSION);
        seedCatalog(OLD, "a".repeat(64));
        assign(OLD, NOW.minusSeconds(7200), null, null);
        jdbc.update("""
                update operations.rbac_catalog_versions
                set status = 'RETIRED', retired_at = ? where catalog_version = ?
                """, timestamp(NOW.minusSeconds(3600)), OLD);
        seedCatalog(CURRENT, "b".repeat(64));
        assign(CURRENT, NOW.minusSeconds(300), NOW.plusSeconds(3600), null);
        assign(CURRENT, NOW.plusSeconds(300), NOW.plusSeconds(3600), null);
        assign(CURRENT, NOW.minusSeconds(600), NOW.minusSeconds(1), null);
        assign(CURRENT, NOW.minusSeconds(500), NOW.plusSeconds(3600), NOW.minusSeconds(100));
    }

    private void seedCatalog(String version, String hash) {
        jdbc.update("""
                insert into operations.rbac_catalog_versions
                    (catalog_version, content_hash, status)
                values (?, ?, 'DRAFT')
                """, version, hash);
        jdbc.update("""
                insert into operations.rbac_catalog_roles
                    (catalog_version, role_id, hierarchy_rank, role_status)
                values (?, ?, 10, 'ACTIVE')
                """, version, ROLE);
        jdbc.update("""
                insert into operations.rbac_catalog_permissions
                    (catalog_version, permission_id, permission_status)
                values (?, ?, 'ACTIVE')
                """, version, PERMISSION);
        jdbc.update("""
                insert into operations.rbac_catalog_role_permissions
                    (catalog_version, role_id, permission_id, delegable)
                values (?, ?, ?, true)
                """, version, ROLE, PERMISSION);
        jdbc.update("""
                update operations.rbac_catalog_versions
                set status = 'ACTIVE', activated_at = ? where catalog_version = ?
                """, timestamp(NOW.minusSeconds(7200)), version);
    }

    private void assign(String catalog, Instant granted, Instant expires, Instant revoked) {
        jdbc.update("""
                insert into operations.operator_role_assignments
                    (operator_account_id, role_id, catalog_version, granted_by_operator_id,
                     granted_at, expires_at, revoked_by_operator_id, revoked_at,
                     revocation_reason_code)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, OPERATOR, ROLE, catalog, OPERATOR, timestamp(granted), timestamp(expires),
                revoked == null ? null : OPERATOR, timestamp(revoked),
                revoked == null ? null : "A22_TEST_REVOKED");
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a2200000-0000-4000-8000-" + "%012d".formatted(suffix));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(OperatorRbacReadPersistenceAdapter.class)
    static class TestApplication {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }
}

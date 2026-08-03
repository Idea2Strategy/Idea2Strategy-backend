package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class OperatorTrustUpgradeMigrationIntegrationTest {
    private static final String MIGRATION = "V20260802232000__backend_operator_trust.sql";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @TempDir Path temporaryDirectory;

    @Test
    void leavesUnverifiedLegacyMappingsUnversionedAndRequiresPositiveExplicitVersions() throws Exception {
        Path central = Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        Path beforeA22 = Files.createDirectories(temporaryDirectory.resolve("before-a22"));
        try (var files = Files.list(central)) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                if (source.getFileName().toString().compareTo(MIGRATION) < 0) {
                    Files.copy(source, beforeA22.resolve(source.getFileName()));
                }
            }
        }
        migrate(beforeA22);
        UUID legacy = insertOperator("legacy-map", null);

        Path throughTrust = Files.createDirectories(temporaryDirectory.resolve("through-trust"));
        try (var files = Files.list(central)) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                if (source.getFileName().toString().compareTo(MIGRATION) <= 0) {
                    Files.copy(source, throughTrust.resolve(source.getFileName()));
                }
            }
        }
        migrate(throughTrust);

        assertNull(scalar("select external_identity_key_version from operations.operator_accounts where id = ?", legacy));
        assertEquals("operations.operator_bootstrap_receipts",
                scalar("select to_regclass('operations.operator_bootstrap_receipts')::text", null));
        assertThrows(SQLException.class, () -> insertOperator("new-unversioned-map", null));
        assertThrows(SQLException.class, () -> insertOperator("invalid-map", -1));
        UUID versioned = insertOperator("versioned-map", 1);
        assertEquals("1", scalar("select external_identity_key_version from operations.operator_accounts where id = ?", versioned));

        updateLegacyStatus(legacy, "DISABLED");
        assertEquals("DISABLED", scalar("select status from operations.operator_accounts where id = ?", legacy));
        assertThrows(SQLException.class, () -> clearVersion(versioned));

        UUID assignment = createActiveAssignment(versioned);
        UUID auditEvent = createAuditEvent(versioned);
        insertReceipt("bootstrap-a", "a".repeat(64), versioned, assignment, 1, auditEvent);
        assertThrows(SQLException.class, () -> execute(
                "update operations.operator_bootstrap_receipts set catalog_version = ? where bootstrap_key = ?",
                "catalog-v1", "bootstrap-a"));
        assertThrows(SQLException.class, () -> execute(
                "delete from operations.operator_bootstrap_receipts where bootstrap_key = ?", "bootstrap-a"));

        UUID anotherOperator = insertOperator("another-versioned-map", 2);
        UUID anotherAudit = createAuditEvent(anotherOperator);
        assertThrows(SQLException.class, () -> insertReceipt(
                "bootstrap-b", "b".repeat(64), anotherOperator, assignment, 2, anotherAudit));
        assertThrows(FlywayException.class, () -> migrate(central));
    }

    private UUID insertOperator(String digest, Integer version) throws Exception {
        UUID id = UUID.randomUUID();
        String sql = version == null ? """
                insert into operations.operator_accounts
                    (id, external_identity_key_hmac, status, mfa_enrolled_at, created_at)
                values (?, ?, 'ACTIVE', clock_timestamp(), clock_timestamp())
                """ : """
                insert into operations.operator_accounts
                    (id, external_identity_key_hmac, external_identity_key_version,
                     status, mfa_enrolled_at, created_at)
                values (?, ?, ?, 'ACTIVE', clock_timestamp(), clock_timestamp())
                """;
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.setString(2, digest);
            if (version != null) statement.setObject(3, version);
            statement.executeUpdate();
        }
        return id;
    }

    private void updateLegacyStatus(UUID id, String status) throws Exception {
        execute("update operations.operator_accounts set status = ? where id = ?", status, id);
    }

    private void clearVersion(UUID id) throws Exception {
        execute("update operations.operator_accounts set external_identity_key_version = null where id = ?", id);
    }

    private UUID createActiveAssignment(UUID operator) throws Exception {
        UUID role = UUID.randomUUID();
        UUID assignment = UUID.randomUUID();
        execute("insert into operations.roles (id, code, hierarchy_rank, status) values (?, ?, 1, 'ACTIVE')",
                role, "BOOTSTRAP_ADMIN");
        execute("insert into operations.rbac_catalog_versions (catalog_version, content_hash, status) values (?, ?, 'DRAFT')",
                "catalog-v1", "c".repeat(64));
        execute("insert into operations.rbac_catalog_roles (catalog_version, role_id, hierarchy_rank, role_status) values (?, ?, 1, 'ACTIVE')",
                "catalog-v1", role);
        execute("update operations.rbac_catalog_versions set status = 'ACTIVE', activated_at = clock_timestamp() where catalog_version = ?",
                "catalog-v1");
        execute("""
                insert into operations.operator_role_assignments
                    (id, operator_account_id, role_id, granted_by_operator_id, granted_at, catalog_version)
                values (?, ?, ?, ?, clock_timestamp(), ?)
                """, assignment, operator, role, operator, "catalog-v1");
        return assignment;
    }

    private UUID createAuditEvent(UUID operator) throws Exception {
        UUID audit = UUID.randomUUID();
        execute("""
                insert into operations.audit_events
                    (id, actor_type, actor_id, action_type, target_domain, target_id, reason_code,
                     correlation_id, idempotency_key, occurred_at)
                values (?, 'OPERATOR', ?, 'BOOTSTRAP', 'OPERATOR_BOOTSTRAP', ?, 'DEPLOYMENT',
                        ?, ?, clock_timestamp())
                """, audit, operator, operator, UUID.randomUUID(), "audit-" + audit);
        return audit;
    }

    private void insertReceipt(
            String key, String manifestHash, UUID operator, UUID assignment, int keyVersion, UUID audit)
            throws Exception {
        execute("""
                insert into operations.operator_bootstrap_receipts
                    (bootstrap_key, manifest_hash, catalog_version, operator_account_id,
                     operator_role_assignment_id, external_identity_key_version,
                     correlation_id, audit_event_id, applied_at)
                values (?, ?, 'catalog-v1', ?, ?, ?, ?, ?, clock_timestamp())
                """, key, manifestHash, operator, assignment, keyVersion, UUID.randomUUID(), audit);
    }

    private void execute(String sql, Object... values) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    private String scalar(String sql, UUID id) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement(sql)) {
            if (id != null) statement.setObject(1, id);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private void migrate(Path directory) {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + directory.toAbsolutePath()).load().migrate();
    }
}

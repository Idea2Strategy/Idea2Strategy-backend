package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class OperatorRbacUpgradeMigrationIntegrationTest {
    private static final String MIGRATION = "V20260802231400__backend_operator_rbac.sql";

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    @TempDir Path temporaryDirectory;

    @Test
    void preservesLegacyAssignmentsAsUnauthorizedAndCreatesNoCatalogSeed() throws Exception {
        Path central = Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        Path before = Files.createDirectories(temporaryDirectory.resolve("before-a13"));
        try (var files = Files.list(central)) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                String fileName = source.getFileName().toString();
                if (!fileName.startsWith("V") || fileName.compareTo(MIGRATION) < 0) {
                    Files.copy(source, before.resolve(source.getFileName()));
                }
            }
        }
        migrate(before);
        UUID actor = operator("legacy-actor");
        UUID target = operator("legacy-target");
        UUID role = UUID.randomUUID();
        execute("insert into operations.roles (id, code, hierarchy_rank, status) values (?, 'REVIEW_EXAMPLE_ROLE', 1, 'ACTIVE')", role);
        UUID legacy = UUID.randomUUID();
        execute("""
                insert into operations.operator_role_assignments
                    (id, operator_account_id, role_id, granted_by_operator_id, granted_at)
                values (?, ?, ?, ?, clock_timestamp())
                """, legacy, target, role, actor);

        migrate(central);

        assertEquals(0, scalar("select count(*) from operations.rbac_catalog_versions"));
        assertEquals(null, scalarText("select catalog_version from operations.operator_role_assignments where id = ?", legacy));
        assertThrows(Exception.class, () -> execute("""
                insert into operations.operator_role_assignments
                    (operator_account_id, role_id, granted_by_operator_id, granted_at)
                values (?, ?, ?, clock_timestamp())
                """, target, role, actor));
    }

    private UUID operator(String key) throws Exception {
        UUID id = UUID.randomUUID();
        execute("""
                insert into operations.operator_accounts
                    (id, external_identity_key_hmac, status, mfa_enrolled_at, created_at)
                values (?, ?, 'ACTIVE', clock_timestamp(), clock_timestamp())
                """, id, key + id);
        return id;
    }

    private void execute(String sql, Object... args) throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            statement.executeUpdate();
        }
    }

    private int scalar(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next(); return result.getInt(1);
        }
    }

    private String scalarText(String sql, Object arg) throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, arg);
            try (var result = statement.executeQuery()) { result.next(); return result.getString(1); }
        }
    }

    private void migrate(Path directory) {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + directory.toAbsolutePath()).load().migrate();
    }
}

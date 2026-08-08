package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class CentralFlywayIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @TempDir
    Path temporaryDirectory;

    @Test
    void migratesOnceAndHasNoPendingWorkOnTheSecondRun() throws Exception {
        var centralDirectory = Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        var bundle = CanonicalMigrationBundleAssembler.assemble(
                centralDirectory, java.util.List.of(), temporaryDirectory.resolve("bundle"));
        var flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + bundle.directory())
                .load();

        int pendingBeforeMigration = flyway.info().pending().length;
        var first = flyway.migrate();
        var second = flyway.migrate();

        assertTrue(pendingBeforeMigration > 0);
        assertEquals(pendingBeforeMigration, first.migrationsExecuted);
        assertEquals(0, second.migrationsExecuted);
        assertTrue(flyway.validateWithResult().validationSuccessful);

        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            try (var roles = statement.executeQuery(
                    "SELECT rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolbypassrls, rolinherit "
                            + "FROM pg_roles WHERE rolname = 'idea2strategy_pipeline'")) {
                assertTrue(roles.next());
                assertFalse(roles.getBoolean("rolcanlogin"));
                assertFalse(roles.getBoolean("rolsuper"));
                assertFalse(roles.getBoolean("rolcreatedb"));
                assertFalse(roles.getBoolean("rolcreaterole"));
                assertFalse(roles.getBoolean("rolbypassrls"));
                assertFalse(roles.getBoolean("rolinherit"));
            }

            var ownership = DatabaseAccessPolicy.ownershipManifest(bundle.orderedFileNames().stream()
                    .filter(name -> name.startsWith("V") && name.endsWith(".sql"))
                    .map(name -> {
                        try {
                            return Files.readString(bundle.directory().resolve(name));
                        } catch (java.io.IOException exception) {
                            throw new java.io.UncheckedIOException(exception);
                        }
                    })
                    .toList());
            for (var role : DatabaseAccessPolicy.ApplicationRole.values()) {
                for (var table : ownership.tables()) {
                    for (var access : java.util.List.of(
                            DatabaseAccessPolicy.Access.READ,
                            DatabaseAccessPolicy.Access.INSERT,
                            DatabaseAccessPolicy.Access.UPDATE,
                            DatabaseAccessPolicy.Access.DELETE)) {
                        var privilege = access == DatabaseAccessPolicy.Access.READ ? "SELECT" : access.name();
                        try (var check = connection.prepareStatement("SELECT has_table_privilege(?, ?, ?)")) {
                            check.setString(1, DatabaseAccessPolicy.databaseRole(role));
                            check.setString(2, table.schema() + "." + table.table());
                            check.setString(3, privilege);
                            try (var result = check.executeQuery()) {
                                assertTrue(result.next());
                                assertEquals(
                                        DatabaseAccessPolicy.allows(role, access, table.schema(), table.table()),
                                        result.getBoolean(1),
                                        () -> role + " " + privilege + " " + table);
                            }
                        }
                    }
                }
            }

            var deniedCrossOwnerWrites = java.util.Map.of(
                    DatabaseAccessPolicy.ApplicationRole.BACKEND, "DELETE FROM backtest.runs WHERE false",
                    DatabaseAccessPolicy.ApplicationRole.BATCH, "DELETE FROM backtest.runs WHERE false",
                    DatabaseAccessPolicy.ApplicationRole.TRADING, "DELETE FROM backtest.runs WHERE false",
                    DatabaseAccessPolicy.ApplicationRole.BACKTEST,
                            "DELETE FROM market_data.dataset_manifests WHERE false",
                    DatabaseAccessPolicy.ApplicationRole.PIPELINE, "DELETE FROM backtest.runs WHERE false");
            for (var deniedWrite : deniedCrossOwnerWrites.entrySet()) {
                statement.execute("SET ROLE " + DatabaseAccessPolicy.databaseRole(deniedWrite.getKey()));
                var denied = assertThrows(SQLException.class, () -> statement.execute(deniedWrite.getValue()));
                assertEquals("42501", denied.getSQLState(), deniedWrite.getKey().toString());
                statement.execute("RESET ROLE");
            }

            statement.execute("SET ROLE idea2strategy_pipeline");
            statement.execute("SELECT * FROM market_data.dataset_manifests LIMIT 0");
            statement.execute("SELECT status, disabled_at FROM operations.operator_accounts LIMIT 0");
            statement.execute("SELECT actor_id, target_id, response_document FROM operations.audit_events LIMIT 0");
            var operatorWriteDenied = assertThrows(
                    SQLException.class,
                    () -> statement.execute("UPDATE operations.operator_accounts SET status = status WHERE false"));
            assertEquals("42501", operatorWriteDenied.getSQLState());
            statement.execute("ROLLBACK");
            statement.execute("SET ROLE idea2strategy_pipeline");
            var auditWriteDenied = assertThrows(
                    SQLException.class,
                    () -> statement.execute("DELETE FROM operations.audit_events WHERE false"));
            assertEquals("42501", auditWriteDenied.getSQLState());
            statement.execute("ROLLBACK");
            statement.execute("SET ROLE idea2strategy_pipeline");
            statement.execute("RESET ROLE");

            statement.execute("SET ROLE idea2strategy_backend");
            try (var pinPrivileges = statement.executeQuery(
                    "SELECT has_table_privilege('idea2strategy_backend', 'backtest.run_input_pins', 'INSERT'), "
                            + "has_table_privilege('idea2strategy_backend', 'backtest.run_input_pins', 'UPDATE')")) {
                assertTrue(pinPrivileges.next());
                assertTrue(pinPrivileges.getBoolean(1));
                assertFalse(pinPrivileges.getBoolean(2));
            }
            // backend #241. 공식 릴리스가 입력 핀을 고정할 때 실행하는 존재 확인 읽기를 실제
            // 런타임 역할로 돌린다. 행 잠금이 없으므로 SELECT, INSERT 권한만으로 통과해야 한다.
            // 이 두 질의가 이 결함의 양쪽 면이다 -- 아래 것은 통과해야 하고 위 것은 거부되어야 한다.
            String existencePinRead =
                    "SELECT p.input_bundle_id, p.input_bundle_fingerprint, p.input_contract_version, "
                            + "p.compiled_plan_checksum, p.strategy_snapshot_hash, p.execution_policy_version, "
                            + "b.bundle_hash FROM backtest.run_input_pins p "
                            + "JOIN backtest.input_bundles b ON b.id = p.input_bundle_id "
                            + "WHERE p.run_id = '00000000-0000-4000-8000-000000000000'";
            statement.execute(existencePinRead);
            var rowLockDenied = assertThrows(
                    SQLException.class,
                    () -> statement.execute(existencePinRead + " FOR UPDATE OF p, b"));
            assertEquals(
                    "42501",
                    rowLockDenied.getSQLState(),
                    "FOR UPDATE 는 UPDATE 권한을 요구하므로 이 역할에서 거부되어야 한다. "
                            + "거부되지 않는다면 최소권한 계약이 느슨해진 것이다.");
            statement.execute("ROLLBACK");
            statement.execute("SET ROLE idea2strategy_backend");

            var ddlDenied = assertThrows(
                    SQLException.class,
                    () -> statement.execute("CREATE TABLE market_data.unauthorized (id integer)"));
            assertEquals("42501", ddlDenied.getSQLState());
            statement.execute("RESET ROLE");
        }
    }
}

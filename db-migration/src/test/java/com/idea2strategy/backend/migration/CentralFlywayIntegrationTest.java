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

            // The statement shapes the trading worker executes against operations, under its own role.
            // WHERE false keeps the rows out; PostgreSQL checks every privilege before execution anyway,
            // including SELECT on an ON CONFLICT arbiter and UPDATE for FOR UPDATE.
            statement.execute("SET ROLE idea2strategy_trading");
            for (var allowed : java.util.List.of(
                    "INSERT INTO operations.outbox_consumer_receipts (consumer_handler_id, outbox_message_id, "
                            + "producer_idempotency_key, payload_hash, status, first_received_at, last_received_at) "
                            + "SELECT 'grant-check', message.id, message.producer_idempotency_key, message.payload_hash, "
                            + "'PROCESSING', now(), now() FROM operations.outbox_messages message "
                            + "LEFT JOIN operations.outbox_consumer_receipts existing "
                            + "ON existing.outbox_message_id = message.id WHERE false "
                            + "ON CONFLICT (consumer_handler_id, outbox_message_id) DO UPDATE SET "
                            + "receive_attempt_count = operations.outbox_consumer_receipts.receive_attempt_count + 1 "
                            + "RETURNING outbox_message_id, claim_token, receive_attempt_count",
                    "SELECT claim_token FROM operations.outbox_consumer_receipts WHERE false FOR UPDATE",
                    "UPDATE operations.outbox_consumer_receipts SET failure_code = NULL WHERE false",
                    "INSERT INTO operations.outbox_messages (id, owner_domain, aggregate_id, aggregate_sequence, "
                            + "event_type, event_schema_version, payload_document, idempotency_key, created_at) "
                            + "SELECT gen_random_uuid(), 'trading', gen_random_uuid(), 1, 'GRANT_CHECK', 'v1', "
                            + "'{}'::jsonb, 'grant-check', now() WHERE false "
                            + "ON CONFLICT (idempotency_key) DO NOTHING",
                    "INSERT INTO operations.audit_events (id, actor_type, actor_id, action_type, target_domain, "
                            + "target_id, reason_code, correlation_id, idempotency_key, occurred_at) "
                            + "SELECT gen_random_uuid(), 'SYSTEM', gen_random_uuid(), 'GRANT_CHECK', 'trading', "
                            + "gen_random_uuid(), 'GRANT_CHECK', gen_random_uuid(), 'grant-check', now() WHERE false "
                            + "ON CONFLICT (idempotency_key) DO NOTHING")) {
                statement.execute(allowed);
            }
            for (var denied : java.util.List.of(
                    "SELECT * FROM operations.operator_login_credentials LIMIT 0",
                    "SELECT * FROM operations.operator_sessions LIMIT 0",
                    "SELECT * FROM operations.operator_accounts LIMIT 0",
                    "SELECT * FROM operations.roles LIMIT 0",
                    "SELECT actor_id, response_document FROM operations.audit_events LIMIT 0",
                    "DELETE FROM operations.audit_events WHERE false",
                    "DELETE FROM operations.outbox_consumer_receipts WHERE false",
                    "UPDATE operations.outbox_messages SET published_at = published_at WHERE false",
                    "INSERT INTO operations.cases (id) SELECT gen_random_uuid() WHERE false")) {
                var failure = assertThrows(SQLException.class, () -> statement.execute(denied), denied);
                assertEquals("42501", failure.getSQLState(), denied);
                statement.execute("ROLLBACK");
                statement.execute("SET ROLE idea2strategy_trading");
            }
            try (var columns = statement.executeQuery(
                    "SELECT has_column_privilege('operations.audit_events', 'idempotency_key', 'SELECT'), "
                            + "has_column_privilege('operations.audit_events', 'actor_id', 'SELECT')")) {
                assertTrue(columns.next());
                assertTrue(columns.getBoolean(1));
                assertFalse(columns.getBoolean(2));
            }
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

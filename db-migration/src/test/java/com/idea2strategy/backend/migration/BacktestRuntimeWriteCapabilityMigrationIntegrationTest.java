package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class BacktestRuntimeWriteCapabilityMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @TempDir
    Path temporaryDirectory;

    @Test
    void runtimeRoleUsesFencedCapabilitiesInsteadOfForgingAttemptOrPublicationRows()
            throws Exception {
        var centralDirectory = Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        var bundle = CanonicalMigrationBundleAssembler.assemble(
                centralDirectory, java.util.List.of(), temporaryDirectory.resolve("bundle"));
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + bundle.directory())
                .load()
                .migrate();

        var runId = UUID.randomUUID();
        var objectId = UUID.randomUUID();
        var objectKey = "backtest-results/" + runId
                + "/TASK6/week_start=2026-01-05/part=0001/" + hash() + ".parquet";
        insertQueuedRun(runId);
        try (var connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(true);
            try (var statement = connection.createStatement()) {
                statement.execute("SET ROLE idea2strategy_backtest");
                try (var privileges = statement.executeQuery("""
                        SELECT
                          has_table_privilege(current_user, 'backtest.run_attempts', 'INSERT'),
                          has_table_privilege(current_user, 'backtest.run_attempts', 'UPDATE'),
                          has_table_privilege(current_user, 'storage.objects', 'INSERT'),
                          has_table_privilege(current_user, 'storage.objects', 'UPDATE')
                        """)) {
                    assertTrue(privileges.next());
                    assertFalse(privileges.getBoolean(1));
                    assertFalse(privileges.getBoolean(2));
                    assertFalse(privileges.getBoolean(3));
                    assertFalse(privileges.getBoolean(4));
                }
                var directAttempt = assertThrows(
                        SQLException.class,
                        () -> statement.execute("INSERT INTO backtest.run_attempts "
                                + "(run_id,attempt_number,worker_execution_key,status,started_at) VALUES ('"
                                + runId + "',1,'FORGED','RUNNING',clock_timestamp())"));
                assertEquals("42501", directAttempt.getSQLState());
                var directPublication = assertThrows(
                        SQLException.class,
                        () -> statement.execute("UPDATE storage.objects SET status='AVAILABLE' WHERE false"));
                assertEquals("42501", directPublication.getSQLState());
                statement.execute("RESET ROLE");
            }

            connection.setAutoCommit(false);
            UUID attemptId;
            UUID claimToken;
            String cleanupCapability;
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL ROLE idea2strategy_backtest");
                }
                try (PreparedStatement claim = connection.prepareStatement(
                        "SELECT id,claim_token FROM backtest.claim_run_attempt(?,?,?,?)")) {
                    claim.setObject(1, runId);
                    claim.setString(2, "task6-owner");
                    claim.setString(3, "TASK6:FENCED");
                    claim.setLong(4, 60_000L);
                    try (var rows = claim.executeQuery()) {
                        assertTrue(rows.next());
                        attemptId = rows.getObject(1, UUID.class);
                        claimToken = rows.getObject(2, UUID.class);
                        assertFalse(rows.next());
                    }
                }
                try (var statement = connection.createStatement();
                        var capability = statement.executeQuery(
                                "SELECT current_setting('idea2strategy.backtest_attempt_cleanup_capability')")) {
                    assertTrue(capability.next());
                    cleanupCapability = capability.getString(1);
                    assertEquals(64, cleanupCapability.length());
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }

            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL ROLE idea2strategy_backtest");
                }
                setAttemptContext(connection, runId, attemptId, claimToken, cleanupCapability);
                try (PreparedStatement token = connection.prepareStatement(
                        "SELECT set_config('idea2strategy.backtest_cleanup_token_hash', "
                                + "encode(public.digest(?, 'sha256'), 'hex'), true)")) {
                    token.setString(1, "a".repeat(64));
                    token.execute();
                }
                try (PreparedStatement register = connection.prepareStatement(
                        "SELECT id,status FROM storage.register_backtest_object(?::jsonb)")) {
                    register.setString(1, objectDocument(objectId, objectKey));
                    try (var rows = register.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals(objectId, rows.getObject(1, UUID.class));
                        assertEquals("STAGED", rows.getString(2));
                        assertFalse(rows.next());
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }

            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL ROLE idea2strategy_backtest");
                }
                setAttemptContext(connection, runId, attemptId, claimToken, cleanupCapability);
                try (PreparedStatement transition = connection.prepareStatement(
                        "SELECT status FROM storage.transition_backtest_object(?, 'AVAILABLE', clock_timestamp())")) {
                    transition.setObject(1, objectId);
                    try (var rows = transition.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals("AVAILABLE", rows.getString(1));
                        assertFalse(rows.next());
                    }
                }
                try (PreparedStatement heartbeat = connection.prepareStatement(
                        "SELECT id FROM backtest.heartbeat_run_attempt(?,?,?)")) {
                    heartbeat.setObject(1, attemptId);
                    heartbeat.setObject(2, claimToken);
                    heartbeat.setLong(3, 60_000L);
                    try (var rows = heartbeat.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals(attemptId, rows.getObject(1, UUID.class));
                        assertFalse(rows.next());
                    }
                }
                try (PreparedStatement close = connection.prepareStatement(
                        "SELECT status FROM backtest.close_run_attempt(?,?,?,?,?,?)")) {
                    close.setObject(1, attemptId);
                    close.setObject(2, claimToken);
                    close.setString(3, "SUCCEEDED");
                    close.setString(4, "SUCCEEDED");
                    close.setString(5, null);
                    close.setBoolean(6, false);
                    try (var rows = close.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals("SUCCEEDED", rows.getString(1));
                        assertFalse(rows.next());
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }

        try (var connection = POSTGRES.createConnection("");
                var verify = connection.prepareStatement(
                        "SELECT o.status, count(ownership.object_id), attempt.status "
                                + "FROM storage.objects o "
                                + "LEFT JOIN storage.backtest_object_ownerships ownership "
                                + "ON ownership.object_id=o.id "
                                + "JOIN backtest.run_attempts attempt ON attempt.run_id=? "
                                + "WHERE o.id=? GROUP BY o.status,attempt.status")) {
            verify.setObject(1, runId);
            verify.setObject(2, objectId);
            try (var rows = verify.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("AVAILABLE", rows.getString(1));
                assertEquals(1, rows.getInt(2));
                assertEquals("SUCCEEDED", rows.getString(3));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void attemptCapabilitiesRejectUnfencedCancellationAndRecoveryOutcomes()
            throws Exception {
        var centralDirectory = Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        var bundle = CanonicalMigrationBundleAssembler.assemble(
                centralDirectory, java.util.List.of(), temporaryDirectory.resolve("fence-bundle"));
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + bundle.directory())
                .load()
                .migrate();

        var cancelledRunId = UUID.randomUUID();
        insertQueuedRun(cancelledRunId);
        UUID cancelledAttemptId;
        try (var connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL ROLE idea2strategy_backtest");
            }
            try (PreparedStatement claim = connection.prepareStatement(
                    "SELECT id FROM backtest.claim_run_attempt(?,?,?,?)")) {
                claim.setObject(1, cancelledRunId);
                claim.setString(2, "task6-fence-owner");
                claim.setString(3, "TASK6:FENCE-CANCEL");
                claim.setLong(4, 60_000L);
                try (var rows = claim.executeQuery()) {
                    assertTrue(rows.next());
                    cancelledAttemptId = rows.getObject(1, UUID.class);
                }
            }
            connection.commit();
        }
        try (var connection = POSTGRES.createConnection("");
                var cancellation = connection.prepareStatement(
                        "UPDATE backtest.runs SET cancellation_requested_at=clock_timestamp(), "
                                + "cancellation_reason_code='USER_CANCELLED' WHERE id=?")) {
            cancellation.setObject(1, cancelledRunId);
            assertEquals(1, cancellation.executeUpdate());
        }
        try (var connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL ROLE idea2strategy_backtest");
            }
            try (PreparedStatement close = connection.prepareStatement(
                    "SELECT status FROM backtest.close_run_attempt(?,?,?,?,?,?)")) {
                close.setObject(1, cancelledAttemptId);
                close.setObject(2, UUID.randomUUID());
                close.setString(3, "SUCCEEDED");
                close.setString(4, "SUCCEEDED");
                close.setString(5, null);
                close.setBoolean(6, false);
                try (var rows = close.executeQuery()) {
                    assertFalse(rows.next(), "a wrong fence must close nothing");
                }
            }
            connection.commit();
        }
        try (var connection = POSTGRES.createConnection("");
                var verify = connection.prepareStatement(
                        "SELECT r.status,a.status FROM backtest.runs r "
                                + "JOIN backtest.run_attempts a ON a.run_id=r.id WHERE r.id=?")) {
            verify.setObject(1, cancelledRunId);
            try (var rows = verify.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("RUNNING", rows.getString(1));
                assertEquals("RUNNING", rows.getString(2));
            }
        }

        var recoveryRunId = UUID.randomUUID();
        insertQueuedRun(recoveryRunId);
        UUID recoveryAttemptId;
        try (var connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL ROLE idea2strategy_backtest");
            }
            try (PreparedStatement claim = connection.prepareStatement(
                    "SELECT id FROM backtest.claim_run_attempt(?,?,?,?)")) {
                claim.setObject(1, recoveryRunId);
                claim.setString(2, "task6-recovery-owner");
                claim.setString(3, "TASK6:FENCE-RECOVERY");
                claim.setLong(4, 60_000L);
                try (var rows = claim.executeQuery()) {
                    assertTrue(rows.next());
                    recoveryAttemptId = rows.getObject(1, UUID.class);
                }
            }
            connection.commit();
        }
        try (var connection = POSTGRES.createConnection("");
                var expire = connection.prepareStatement(
                        "UPDATE backtest.run_attempts SET "
                                + "started_at=clock_timestamp()-interval '3 minutes', "
                                + "claimed_at=clock_timestamp()-interval '3 minutes', "
                                + "last_heartbeat_at=clock_timestamp()-interval '2 minutes', "
                                + "claim_expires_at=clock_timestamp()-interval '1 minute' "
                                + "WHERE id=?")) {
            expire.setObject(1, recoveryAttemptId);
            assertEquals(1, expire.executeUpdate());
        }
        try (var connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL ROLE idea2strategy_backtest");
            }
            try (PreparedStatement recover = connection.prepareStatement(
                    "SELECT backtest.recover_expired_run_attempt(?, 'CANCELLED', 'CANCELLED_BY_REQUEST')")) {
                recover.setObject(1, recoveryAttemptId);
                try (var rows = recover.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1), "recovery cannot invent a cancellation request");
                }
            }
            connection.commit();
        }
    }

    private void insertQueuedRun(UUID runId) throws SQLException {
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("SET LOCAL session_replication_role = replica");
                statement.execute("""
                    INSERT INTO backtest.runs (
                        id,bot_id,owner_account_id,configuration_hash,status,
                        evaluation_start,evaluation_end,initial_cash_amount,
                        market_rules_version,accounting_rules_version,precision_rules_version,
                        fee_policy_id,slippage_rate_bps,buying_power_buffer_policy_id,
                        idempotency_key,queued_at,owner_anonymized_at,lane,message_id,
                        canonical_payload_hash,aggregate_sequence,execution_policy_version,
                        idempotency_scope
                    ) VALUES (
                        '%s','%s',NULL,'sha256:%s','QUEUED','2026-01-01','2026-01-02',1000,
                        'market:1','accounting:1','precision:1','%s',0,'%s','TASK6:%s',
                        clock_timestamp(),clock_timestamp(),'BASIC','%s','sha256:%s',1,
                        'policy:1','TASK6'
                    )
                        """.formatted(
                                runId,
                                UUID.randomUUID(),
                                hash(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                hash()));
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private static void setAttemptContext(
            Connection connection,
            UUID runId,
            UUID attemptId,
            UUID claimToken,
            String cleanupCapability) throws SQLException {
        try (PreparedStatement context = connection.prepareStatement("""
                SELECT set_config('idea2strategy.backtest_run_id', ?, true),
                       set_config('idea2strategy.backtest_attempt_id', ?, true),
                       set_config('idea2strategy.backtest_claim_token', ?, true),
                       set_config('idea2strategy.backtest_attempt_cleanup_capability', ?, true)
                """)) {
            context.setString(1, runId.toString());
            context.setString(2, attemptId.toString());
            context.setString(3, claimToken.toString());
            context.setString(4, cleanupCapability);
            context.execute();
        }
    }

    private static String objectDocument(UUID objectId, String objectKey) {
        return """
                {"id":"%s","status":"STAGED","storage_provider":"S3_COMPATIBLE",\
                "bucket_name":"task6","object_key":"%s","provider_version_id":"version-1",\
                "content_hash":"%s","byte_size":1,"file_format":"PARQUET",\
                "compression_codec":"UNCOMPRESSED","media_type":"application/octet-stream",\
                "schema_version":"1.0.0","row_count":1,\
                "period_start":"2026-01-01T00:00:00Z",\
                "period_end":"2026-01-01T00:00:01Z","encryption_key_ref":null,\
                "retention_policy_version":"v1","retention_until":null,"legal_hold":false,\
                "created_at":"2026-01-01T00:00:00Z","verified_at":null,\
                "quarantined_at":null,"superseded_at":null,"deleted_at":null}
                """.formatted(objectId, objectKey, hash()).replace("\n", "");
    }

    private static String hash() {
        return "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    }
}

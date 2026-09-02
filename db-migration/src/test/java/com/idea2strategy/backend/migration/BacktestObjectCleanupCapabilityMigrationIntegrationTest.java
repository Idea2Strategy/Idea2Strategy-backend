package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class BacktestObjectCleanupCapabilityMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @TempDir
    Path temporaryDirectory;

    @Test
    void runtimeRoleCanOnlyDeleteExactUnreferencedBacktestObjectsThroughTheCapability()
            throws Exception {
        var centralDirectory = Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        var bundle = CanonicalMigrationBundleAssembler.assemble(
                centralDirectory, java.util.List.of(), temporaryDirectory.resolve("bundle"));
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + bundle.directory())
                .load()
                .migrate();

        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            assertCapabilityDefinition(statement);

            var runId = UUID.randomUUID();
            var owner = insertLiveOwner(connection, runId, null);
            var referencedId = UUID.randomUUID();
            var removableId = UUID.randomUUID();
            var unownedId = UUID.randomUUID();
            var referencedToken = cleanupToken("1");
            var removableToken = cleanupToken("2");
            insertOwnedObject(
                    connection, owner, referencedId, canonicalKey(runId, "REFERENCED"), referencedToken);
            insertOwnedObject(
                    connection, owner, removableId, canonicalKey(runId, "REMOVABLE"), removableToken);
            insertObject(connection, unownedId, canonicalKey(runId, "UNOWNED"));
            statement.execute("CREATE SCHEMA task5_future_unreadable");
            statement.execute("""
                    CREATE TABLE task5_future_unreadable.object_refs (
                        object_id uuid REFERENCES storage.objects(id)
                    )
                    """);
            statement.execute("INSERT INTO task5_future_unreadable.object_refs VALUES ('" + referencedId + "')");

            statement.execute("SET ROLE idea2strategy_backtest");
            try (var privileges = statement.executeQuery("""
                    SELECT
                      has_table_privilege(current_user, 'storage.objects', 'DELETE'),
                      has_table_privilege(current_user,
                        (SELECT relation.oid
                         FROM pg_catalog.pg_class relation
                         JOIN pg_catalog.pg_namespace namespace
                           ON namespace.oid = relation.relnamespace
                         WHERE namespace.nspname = 'task5_future_unreadable'
                           AND relation.relname = 'object_refs'),
                        'SELECT'),
                      has_function_privilege(current_user,
                        'storage.prepare_backtest_object_cleanup(jsonb)', 'EXECUTE'),
                      has_function_privilege(current_user,
                        'storage.reissue_backtest_object_cleanup(jsonb,text)', 'EXECUTE'),
                      has_table_privilege(current_user,
                        'storage.backtest_object_ownerships', 'SELECT'),
                      has_table_privilege(current_user,
                        'storage.backtest_attempt_cleanup_capabilities', 'SELECT')
                    """)) {
                assertTrue(privileges.next());
                assertFalse(privileges.getBoolean(1));
                assertFalse(privileges.getBoolean(2));
                assertTrue(privileges.getBoolean(3));
                assertTrue(privileges.getBoolean(4));
                assertFalse(privileges.getBoolean(5));
                assertFalse(privileges.getBoolean(6));
            }
            var directDelete = assertThrows(
                    SQLException.class,
                    () -> statement.execute("DELETE FROM storage.objects WHERE id='" + removableId + "'"));
            assertEquals("42501", directDelete.getSQLState());

            var referenced = assertThrows(
                    SQLException.class,
                    () -> cleanup(
                            connection,
                            owner,
                            referencedId,
                            canonicalKey(runId, "REFERENCED"),
                            referencedToken));
            assertEquals("P0001", referenced.getSQLState(), referenced.getMessage());
            assertTrue(referenced.getMessage().contains("task5_future_unreadable.object_refs"));

            var unowned = assertThrows(
                    SQLException.class,
                    () -> cleanup(
                            connection,
                            owner,
                            unownedId,
                            canonicalKey(runId, "UNOWNED"),
                            cleanupToken("3")));
            assertEquals("P0001", unowned.getSQLState(), unowned.getMessage());
            assertTrue(unowned.getMessage().contains("producer ownership"));

            var wrongToken = assertThrows(
                    SQLException.class,
                    () -> cleanup(
                            connection,
                            owner,
                            removableId,
                            canonicalKey(runId, "REMOVABLE"),
                            cleanupToken("9")));
            assertEquals("P0001", wrongToken.getSQLState(), wrongToken.getMessage());
            assertTrue(wrongToken.getMessage().contains("producer ownership"));

            var extraCandidateField = candidate(
                    removableId,
                    canonicalKey(runId, "REMOVABLE"),
                    removableToken).replace("}", ",\"escalate\":true}");
            var adversarial = assertThrows(
                    SQLException.class,
                    () -> cleanupPayload(connection, owner, extraCandidateField));
            assertEquals("P0001", adversarial.getSQLState(), adversarial.getMessage());
            assertTrue(adversarial.getMessage().contains("shape is invalid"));

            assertEquals(
                    removableId,
                    cleanup(
                            connection,
                            owner,
                            removableId,
                            canonicalKey(runId, "REMOVABLE"),
                            removableToken));
            statement.execute("RESET ROLE");

            assertEquals(1, objectCount(connection, referencedId));
            assertEquals(0, objectCount(connection, removableId));
            assertEquals(1, objectCount(connection, unownedId));
            statement.execute("DROP SCHEMA task5_future_unreadable CASCADE");
            statement.execute("DELETE FROM storage.objects WHERE id IN ('"
                    + referencedId + "','" + unownedId + "')");
        }
    }

    @Test
    void runtimeRoleCannotDeleteCanonicalObjectWithoutDurableProducerOwnership()
            throws Exception {
        var centralDirectory = Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        var bundle = CanonicalMigrationBundleAssembler.assemble(
                centralDirectory, java.util.List.of(), temporaryDirectory.resolve("ownership-bundle"));
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + bundle.directory())
                .load()
                .migrate();

        var runId = UUID.randomUUID();
        var objectId = UUID.randomUUID();
        var objectKey = canonicalKey(runId, "UNOWNED_CANONICAL");
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            var owner = insertLiveOwner(connection, runId, null);
            insertObject(connection, objectId, objectKey);
            statement.execute("SET ROLE idea2strategy_backtest");

            var denied = assertThrows(
                    SQLException.class,
                    () -> cleanup(connection, owner, objectId, objectKey, cleanupToken("4")));
            assertEquals("P0001", denied.getSQLState(), denied.getMessage());
            assertTrue(denied.getMessage().contains("producer ownership"), denied.getMessage());

            statement.execute("RESET ROLE");
            assertEquals(1, objectCount(connection, objectId));
            statement.execute("DELETE FROM storage.objects WHERE id='" + objectId + "'");
        }
    }

    @Test
    void cleanupRejectsEveryUnvalidatedForeignKeyBeforeDeletingCandidateRows()
            throws Exception {
        var centralDirectory = Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        var bundle = CanonicalMigrationBundleAssembler.assemble(
                centralDirectory, java.util.List.of(), temporaryDirectory.resolve("unvalidated-bundle"));
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + bundle.directory())
                .load()
                .migrate();

        var runId = UUID.randomUUID();
        var objectId = UUID.randomUUID();
        var objectKey = canonicalKey(runId, "UNVALIDATED");
        var cleanupToken = cleanupToken("5");
        var schema = "task5_unvalidated_" + objectId.toString().replace("-", "");
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            var owner = insertLiveOwner(connection, runId, null);
            insertOwnedObject(connection, owner, objectId, objectKey, cleanupToken);
            statement.execute("CREATE SCHEMA \"" + schema + "\"");
            statement.execute("""
                    DO $$ BEGIN
                      IF EXISTS (
                        SELECT 1 FROM pg_event_trigger
                        WHERE evtname = 'storage_reject_unvalidated_object_fks'
                      ) THEN
                        EXECUTE 'ALTER EVENT TRIGGER storage_reject_unvalidated_object_fks DISABLE';
                      END IF;
                    END $$
                    """);
            statement.execute("CREATE TABLE \"" + schema + "\".object_refs (object_id uuid)");
            statement.execute("ALTER TABLE \"" + schema + "\".object_refs "
                    + "ADD CONSTRAINT object_refs_storage_fk FOREIGN KEY (object_id) "
                    + "REFERENCES storage.objects(id) NOT VALID");
            statement.execute("""
                    DO $$ BEGIN
                      IF EXISTS (
                        SELECT 1 FROM pg_event_trigger
                        WHERE evtname = 'storage_reject_unvalidated_object_fks'
                      ) THEN
                        EXECUTE 'ALTER EVENT TRIGGER storage_reject_unvalidated_object_fks ENABLE';
                      END IF;
                    END $$
                    """);

            statement.execute("SET ROLE idea2strategy_backtest");
            var denied = assertThrows(
                    SQLException.class,
                    () -> cleanup(connection, owner, objectId, objectKey, cleanupToken));
            assertEquals("P0001", denied.getSQLState(), denied.getMessage());
            assertTrue(denied.getMessage().contains("unvalidated"), denied.getMessage());
            statement.execute("RESET ROLE");
            assertEquals(1, objectCount(connection, objectId));
        } finally {
            try (var connection = POSTGRES.createConnection("");
                    var statement = connection.createStatement()) {
                statement.execute("""
                        DO $$ BEGIN
                          IF EXISTS (
                            SELECT 1 FROM pg_event_trigger
                            WHERE evtname = 'storage_reject_unvalidated_object_fks'
                          ) THEN
                            EXECUTE 'ALTER EVENT TRIGGER storage_reject_unvalidated_object_fks ENABLE';
                          END IF;
                        END $$
                        """);
                statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
                statement.execute("DELETE FROM storage.objects WHERE id='" + objectId + "'");
            }
        }
    }

    @Test
    void eventTriggerRejectsOnlyUnvalidatedStorageObjectForeignKeys() throws Exception {
        var centralDirectory = Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        var bundle = CanonicalMigrationBundleAssembler.assemble(
                centralDirectory, java.util.List.of(), temporaryDirectory.resolve("event-trigger-bundle"));
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + bundle.directory())
                .load()
                .migrate();

        var schema = "task5_event_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + schema + "\"");
            statement.execute("CREATE TABLE \"" + schema + "\".parent_ids (id uuid PRIMARY KEY)");
            statement.execute("CREATE TABLE \"" + schema + "\".unrelated_refs (id uuid)");
            statement.execute("ALTER TABLE \"" + schema + "\".unrelated_refs "
                    + "ADD CONSTRAINT unrelated_not_valid FOREIGN KEY (id) "
                    + "REFERENCES \"" + schema + "\".parent_ids(id) NOT VALID");
            statement.execute("CREATE TABLE \"" + schema + "\".object_refs (object_id uuid)");

            var rejected = assertThrows(
                    SQLException.class,
                    () -> statement.execute("ALTER TABLE \"" + schema + "\".object_refs "
                            + "ADD CONSTRAINT unsafe_storage_fk FOREIGN KEY (object_id) "
                            + "REFERENCES storage.objects(id) NOT VALID"));
            assertEquals("P0001", rejected.getSQLState(), rejected.getMessage());
            assertTrue(rejected.getMessage().contains("unvalidated"));

            statement.execute("ALTER TABLE \"" + schema + "\".object_refs "
                    + "ADD CONSTRAINT validated_storage_fk FOREIGN KEY (object_id) "
                    + "REFERENCES storage.objects(id)");
            try (var trigger = statement.executeQuery("""
                    SELECT event_trigger.evtenabled,
                           procedure.proowner::regrole::text AS owner_name
                    FROM pg_event_trigger event_trigger
                    JOIN pg_proc procedure ON procedure.oid = event_trigger.evtfoid
                    WHERE event_trigger.evtname = 'storage_reject_unvalidated_object_fks'
                    """)) {
                assertTrue(trigger.next());
                assertEquals("O", trigger.getString(1));
                assertNotEquals("idea2strategy_backtest", trigger.getString(2));
                assertFalse(trigger.next());
            }
            statement.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
        }
    }

    private static void assertCapabilityDefinition(Statement statement) throws SQLException {
        try (var definition = statement.executeQuery("""
                SELECT procedure.prosecdef,
                       procedure.proowner::regrole::text AS owner_name,
                       procedure.proconfig,
                       has_function_privilege('public', procedure.oid, 'EXECUTE'),
                       has_function_privilege('idea2strategy_backtest', procedure.oid, 'EXECUTE')
                FROM pg_proc procedure
                WHERE procedure.oid =
                  to_regprocedure('storage.prepare_backtest_object_cleanup(jsonb)')
                """)) {
            assertTrue(definition.next(), "the forward migration must install the cleanup capability");
            assertTrue(definition.getBoolean(1));
            assertNotEquals("idea2strategy_backtest", definition.getString(2));
            var settings = (String[]) definition.getArray(3).getArray();
            assertTrue(java.util.List.of(settings).contains("search_path=pg_catalog, pg_temp"));
            assertTrue(java.util.List.of(settings).contains("lock_timeout=5s"));
            assertFalse(definition.getBoolean(4));
            assertTrue(definition.getBoolean(5));
        }
    }

    private static UUID cleanup(
            Connection connection,
            Owner owner,
            UUID id,
            String key,
            String cleanupToken) throws SQLException {
        return cleanupPayload(connection, owner, candidate(id, key, cleanupToken));
    }

    private static UUID cleanupPayload(Connection connection, Owner owner, String payload)
            throws SQLException {
        var oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            setAttemptContext(connection, owner);
            try (PreparedStatement cleanup = connection.prepareStatement(
                    "SELECT id FROM storage.prepare_backtest_object_cleanup(?::jsonb)")) {
                cleanup.setString(1, payload);
                try (var rows = cleanup.executeQuery()) {
                    assertTrue(rows.next());
                    var removed = rows.getObject(1, UUID.class);
                    assertFalse(rows.next());
                    connection.commit();
                    return removed;
                }
            }
        } catch (SQLException | RuntimeException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    private static String candidate(UUID id, String key, String cleanupToken) {
        return """
                [{"object_id":"%s","storage_provider":"S3_COMPATIBLE",\
                "bucket_name":"task5","object_key":"%s",\
                "provider_version_id":"version-1","content_hash":"%s",\
                "cleanup_token":"%s"}]
                """.formatted(id, key, hash(), cleanupToken).replace("\n", "");
    }

    private static Owner insertLiveOwner(Connection connection, UUID runId, UUID previousAttemptId)
            throws SQLException {
        var attemptId = UUID.randomUUID();
        var claimToken = UUID.randomUUID();
        var oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL session_replication_role = replica");
            statement.execute("""
                    INSERT INTO backtest.runs (
                        id,bot_id,owner_account_id,configuration_hash,status,
                        evaluation_start,evaluation_end,initial_cash_amount,
                        market_rules_version,accounting_rules_version,precision_rules_version,
                        fee_policy_id,slippage_rate_bps,buying_power_buffer_policy_id,
                        idempotency_key,queued_at,started_at,owner_anonymized_at,lane,
                        message_id,canonical_payload_hash,aggregate_sequence,
                        execution_policy_version,idempotency_scope
                    ) VALUES (
                        '%s','%s',NULL,'sha256:%s','RUNNING',
                        '2026-01-01','2026-01-02',1000,
                        'market:1','accounting:1','precision:1',
                        '%s',0,'%s','TASK5:%s',clock_timestamp(),clock_timestamp(),
                        clock_timestamp(),'BASIC','%s','sha256:%s',1,'policy:1','TASK5'
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
            statement.execute("SET LOCAL session_replication_role = origin");
            statement.execute("""
                    INSERT INTO backtest.run_attempts (
                        id,run_id,attempt_number,worker_execution_key,status,started_at,
                        claim_token,worker_id,claimed_at,claim_expires_at,last_heartbeat_at,
                        previous_attempt_id
                    ) VALUES (
                        '%s','%s',%d,'TASK5:%s','RUNNING',clock_timestamp(),
                        '%s','task5-owner',clock_timestamp(),
                        clock_timestamp()+interval '1 hour',clock_timestamp(),%s
                    )
                    """.formatted(
                            attemptId,
                            runId,
                            previousAttemptId == null ? 1 : 2,
                            attemptId,
                            claimToken,
                            previousAttemptId == null ? "NULL" : "'" + previousAttemptId + "'"));
            String capability;
            try (var generated = statement.executeQuery(
                    "SELECT current_setting('idea2strategy.backtest_attempt_cleanup_capability')")) {
                assertTrue(generated.next());
                capability = generated.getString(1);
                assertEquals(64, capability.length());
            }
            connection.commit();
            return new Owner(runId, attemptId, claimToken, capability);
        } catch (SQLException | RuntimeException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    private static void insertOwnedObject(
            Connection connection,
            Owner owner,
            UUID id,
            String key,
            String cleanupToken) throws SQLException {
        var oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            setAttemptContext(connection, owner);
            try (PreparedStatement token = connection.prepareStatement(
                    "SELECT set_config('idea2strategy.backtest_cleanup_token_hash', "
                            + "encode(public.digest(?, 'sha256'), 'hex'), true)")) {
                token.setString(1, cleanupToken);
                token.execute();
            }
            insertObject(connection, id, key);
            connection.commit();
        } catch (SQLException | RuntimeException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    private static void setAttemptContext(Connection connection, Owner owner) throws SQLException {
        try (PreparedStatement context = connection.prepareStatement("""
                SELECT set_config('idea2strategy.backtest_run_id', ?, true),
                       set_config('idea2strategy.backtest_attempt_id', ?, true),
                       set_config('idea2strategy.backtest_claim_token', ?, true),
                       set_config('idea2strategy.backtest_attempt_cleanup_capability', ?, true)
                """)) {
            context.setString(1, owner.runId().toString());
            context.setString(2, owner.attemptId().toString());
            context.setString(3, owner.claimToken().toString());
            context.setString(4, owner.capability());
            context.execute();
        }
    }

    private static void insertObject(Connection connection, UUID id, String key) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO storage.objects
                    (id, status, storage_provider, bucket_name, object_key,
                     provider_version_id, content_hash, byte_size, file_format,
                     compression_codec, media_type, schema_version, row_count,
                     period_start, period_end, retention_policy_version)
                VALUES (?, 'AVAILABLE', 'S3_COMPATIBLE', 'task5', ?, 'version-1', ?, 1,
                        'PARQUET', 'UNCOMPRESSED', 'application/octet-stream', '1.0.0', 1,
                        '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z', 'v1')
                """)) {
            insert.setObject(1, id);
            insert.setString(2, key);
            insert.setString(3, hash());
            insert.executeUpdate();
        }
    }

    private static int objectCount(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement count = connection.prepareStatement(
                "SELECT count(*) FROM storage.objects WHERE id=?")) {
            count.setObject(1, id);
            try (var rows = count.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private static String canonicalKey(UUID runId, String recordType) {
        return "backtest-results/" + runId
                + "/" + recordType + "/week_start=2026-01-05/part=0001/" + hash() + ".parquet";
    }

    private static String cleanupToken(String hexDigit) {
        return hexDigit.repeat(64);
    }

    private static String hash() {
        return "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    }

    private record Owner(UUID runId, UUID attemptId, UUID claimToken, String capability) {}
}

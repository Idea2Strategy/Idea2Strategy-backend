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

            var referencedId = UUID.randomUUID();
            var removableId = UUID.randomUUID();
            var unownedId = UUID.randomUUID();
            insertObject(connection, referencedId, canonicalKey(referencedId));
            insertObject(connection, removableId, canonicalKey(removableId));
            insertObject(connection, unownedId, "pipeline-owned/" + unownedId + "/" + hash() + ".parquet");
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
                        'storage.prepare_backtest_object_cleanup(jsonb)', 'EXECUTE')
                    """)) {
                assertTrue(privileges.next());
                assertFalse(privileges.getBoolean(1));
                assertFalse(privileges.getBoolean(2));
                assertTrue(privileges.getBoolean(3));
            }
            var directDelete = assertThrows(
                    SQLException.class,
                    () -> statement.execute("DELETE FROM storage.objects WHERE id='" + removableId + "'"));
            assertEquals("42501", directDelete.getSQLState());

            var referenced = assertThrows(
                    SQLException.class,
                    () -> cleanup(connection, referencedId, canonicalKey(referencedId)));
            assertEquals("P0001", referenced.getSQLState(), referenced.getMessage());
            assertTrue(referenced.getMessage().contains("task5_future_unreadable.object_refs"));

            var unowned = assertThrows(
                    SQLException.class,
                    () -> cleanup(
                            connection,
                            unownedId,
                            "pipeline-owned/" + unownedId + "/" + hash() + ".parquet"));
            assertEquals("P0001", unowned.getSQLState(), unowned.getMessage());
            assertTrue(unowned.getMessage().contains("canonical backtest"));

            assertEquals(removableId, cleanup(connection, removableId, canonicalKey(removableId)));
            statement.execute("RESET ROLE");

            assertEquals(1, objectCount(connection, referencedId));
            assertEquals(0, objectCount(connection, removableId));
            assertEquals(1, objectCount(connection, unownedId));
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

    private static UUID cleanup(Connection connection, UUID id, String key) throws SQLException {
        var payload = """
                [{"object_id":"%s","storage_provider":"S3_COMPATIBLE",\
                "bucket_name":"task5","object_key":"%s",\
                "provider_version_id":"version-1","content_hash":"%s"}]
                """.formatted(id, key, hash()).replace("\n", "");
        try (PreparedStatement cleanup = connection.prepareStatement(
                "SELECT id FROM storage.prepare_backtest_object_cleanup(?::jsonb)")) {
            cleanup.setString(1, payload);
            try (var rows = cleanup.executeQuery()) {
                assertTrue(rows.next());
                var removed = rows.getObject(1, UUID.class);
                assertFalse(rows.next());
                return removed;
            }
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

    private static String canonicalKey(UUID runId) {
        return "backtest-results/" + runId
                + "/TASK5_CLEANUP/week_start=2026-01-05/part=0001/" + hash() + ".parquet";
    }

    private static String hash() {
        return "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    }
}

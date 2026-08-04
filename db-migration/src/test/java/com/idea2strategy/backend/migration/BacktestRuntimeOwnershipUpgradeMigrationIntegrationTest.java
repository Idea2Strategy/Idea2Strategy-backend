package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class BacktestRuntimeOwnershipUpgradeMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @TempDir
    Path temporaryDirectory;

    @Test
    void permitsRepeatedEmptyManifestHashesButProtectsMaterializedContentAndIsRepeatable() throws Exception {
        var centralDirectory = Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        var bundle = CanonicalMigrationBundleAssembler.assemble(
                centralDirectory, java.util.List.of(), temporaryDirectory.resolve("bundle"));
        var flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + bundle.directory())
                .load();

        flyway.migrate();
        assertEquals(0, flyway.migrate().migrationsExecuted);

        try (var connection = POSTGRES.createConnection("");
                var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO market_data.providers
                        (id, code, display_name, rights_version, status, created_at)
                    VALUES ('10000000-0000-4000-8000-000000000001', 'TEST', 'Test', 'v1', 'ACTIVE', now());
                    INSERT INTO market_data.feeds
                        (id, provider_id, code, data_kind, resolution, timezone_name, feed_version, created_at)
                    VALUES ('20000000-0000-4000-8000-000000000001',
                        '10000000-0000-4000-8000-000000000001', 'TEST-1D', 'BARS', '1d', 'UTC', 'v1', now());
                    INSERT INTO market_data.dataset_manifests
                        (id, feed_id, data_layer, resolution, revision_number, status,
                         period_start, period_end, schema_version, dataset_hash)
                    VALUES
                        ('30000000-0000-4000-8000-000000000001',
                         '20000000-0000-4000-8000-000000000001', 'RAW', '1d', 1, 'QUARANTINED',
                         '2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z', 'v1', 'empty-content-hash'),
                        ('30000000-0000-4000-8000-000000000002',
                         '20000000-0000-4000-8000-000000000001', 'RAW', '1d', 2, 'QUARANTINED',
                         '2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z', 'v1', 'empty-content-hash');
                    INSERT INTO storage.objects
                        (id, status, storage_provider, bucket_name, object_key, provider_version_id,
                         content_hash, byte_size, file_format, compression_codec, media_type,
                         schema_version, retention_policy_version)
                    VALUES
                        ('40000000-0000-4000-8000-000000000001', 'AVAILABLE', 'S3', 'test', 'one', 'v1',
                         'content-one', 1, 'PARQUET', 'NONE', 'application/octet-stream', 'v1', 'v1'),
                        ('40000000-0000-4000-8000-000000000002', 'AVAILABLE', 'S3', 'test', 'two', 'v1',
                         'content-two', 1, 'PARQUET', 'NONE', 'application/octet-stream', 'v1', 'v1');
                    """);

            insertDatasetObject(statement, "50000000-0000-4000-8000-000000000001",
                    "30000000-0000-4000-8000-000000000001", "40000000-0000-4000-8000-000000000001");

            var duplicate = assertThrows(SQLException.class, () -> insertDatasetObject(
                    statement, "50000000-0000-4000-8000-000000000002",
                    "30000000-0000-4000-8000-000000000002", "40000000-0000-4000-8000-000000000002"));
            assertEquals("23505", duplicate.getSQLState());

            try (var counts = statement.executeQuery("""
                    SELECT id, object_count FROM market_data.dataset_manifests
                    WHERE id IN ('30000000-0000-4000-8000-000000000001',
                                 '30000000-0000-4000-8000-000000000002')
                    ORDER BY id
                    """)) {
                counts.next();
                assertEquals(1, counts.getLong("object_count"));
                counts.next();
                assertEquals(0, counts.getLong("object_count"));
            }
        }
    }

    private static void insertDatasetObject(
            java.sql.Statement statement, String id, String manifestId, String objectId) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO market_data.dataset_objects
                    (id, dataset_manifest_id, object_id, object_kind, partition_granularity,
                     partition_start, partition_end, period_start, period_end, shard_key, part_number, row_count)
                VALUES ('%s', '%s', '%s', 'BAR', 'DAY', '2026-01-01', '2026-01-02',
                        '2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z', '%s', 1, 1)
                """.formatted(id, manifestId, objectId, id));
    }
}

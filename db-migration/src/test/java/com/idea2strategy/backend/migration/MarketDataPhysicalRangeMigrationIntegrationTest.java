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
class MarketDataPhysicalRangeMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesPhysicalParquetRangeSeparatelyFromCoverageAndRejectsReversedEvidence() throws Exception {
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
            statement.executeUpdate("""
                    INSERT INTO market_data.providers
                        (id, code, display_name, rights_version, status, created_at)
                    VALUES ('10000000-0000-4000-8000-000000000001', 'TEST', 'Test', 'v1', 'ACTIVE', now());
                    INSERT INTO market_data.feeds
                        (id, provider_id, code, data_kind, resolution, timezone_name, feed_version, created_at)
                    VALUES ('20000000-0000-4000-8000-000000000001',
                        '10000000-0000-4000-8000-000000000001', 'TEST-30M', 'BARS', '30m', 'UTC', 'v1', now());
                    INSERT INTO market_data.dataset_manifests
                        (id, feed_id, data_layer, resolution, revision_number, status,
                         period_start, period_end, actual_start_at, actual_end_at,
                         schema_version, dataset_hash)
                    VALUES ('30000000-0000-4000-8000-000000000001',
                        '20000000-0000-4000-8000-000000000001', 'ADJUSTED', '30m', 1, 'QUARANTINED',
                        '2026-01-01T00:00:00Z', '2027-01-01T00:00:00Z',
                        '2026-01-02T14:30:00Z', '2026-12-31T20:30:00Z', 'v1', 'hash-one');
                    """);

            try (var result = statement.executeQuery("""
                    SELECT actual_start_at, actual_end_at
                    FROM market_data.dataset_manifests
                    WHERE id = '30000000-0000-4000-8000-000000000001'
                    """)) {
                result.next();
                assertEquals("2026-01-02T14:30Z", result.getObject("actual_start_at", java.time.OffsetDateTime.class).toString());
                assertEquals("2026-12-31T20:30Z", result.getObject("actual_end_at", java.time.OffsetDateTime.class).toString());
            }

            SQLException reversed = assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    UPDATE market_data.dataset_manifests
                       SET actual_start_at = '2026-12-31T20:30:00Z',
                           actual_end_at = '2026-01-02T14:30:00Z'
                     WHERE id = '30000000-0000-4000-8000-000000000001'
                    """));
            assertEquals("23514", reversed.getSQLState());
        }
    }
}

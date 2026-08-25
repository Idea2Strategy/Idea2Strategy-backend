package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class BasicStrategyExecutionCompletionMigrationIntegrationTest {
    private static final String V1 = "0f4a0000-0000-4000-8000-000000000001";
    private static final String V2 = "0f5a0000-0000-4000-8000-000000000001";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @TempDir
    Path temporaryDirectory;

    @Test
    void publishesFourteenV2ElementsAndCapsWithoutChangingV1() throws Exception {
        var centralDirectory = Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        var bundle = CanonicalMigrationBundleAssembler.assemble(
                centralDirectory, java.util.List.of(), temporaryDirectory.resolve("bundle"));
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + bundle.directory())
                .load()
                .migrate();

        try (var connection = POSTGRES.createConnection(""); var statement = connection.createStatement()) {
            try (var catalogs = statement.executeQuery("""
                    SELECT catalog_version FROM strategy.element_catalog_versions
                    WHERE id = '%s'
                    """.formatted(V2))) {
                assertTrue(catalogs.next());
                assertEquals("basic-elements:2026-08-25", catalogs.getString(1));
            }
            try (var count = statement.executeQuery("""
                    SELECT count(*) FROM strategy.element_definitions
                    WHERE element_catalog_version_id = '%s'
                    """.formatted(V2))) {
                assertTrue(count.next());
                assertEquals(14, count.getInt(1));
            }
            try (var terminal = statement.executeQuery("""
                    SELECT parameter_schema::text, execution_contract::text
                    FROM strategy.element_definitions
                    WHERE element_catalog_version_id = '%s'
                      AND element_code = 'BASIC_EQUAL_ALLOCATION_ORDER'
                    """.formatted(V2))) {
                assertTrue(terminal.next());
                String schema = terminal.getString(1);
                String contract = terminal.getString(2);
                assertTrue(schema.contains("maxPositionPercent"));
                assertTrue(schema.contains("x-numericExclusiveMinimum"));
                assertTrue(schema.contains("x-numericMaximum"));
                assertTrue(contract.contains("$maxPositionPercent"));
            }
            try (var legacy = statement.executeQuery("""
                    SELECT parameter_schema::text, definition_hash
                    FROM strategy.element_definitions
                    WHERE element_catalog_version_id = '%s'
                      AND element_code = 'BASIC_EQUAL_ALLOCATION_ORDER'
                    """.formatted(V1))) {
                assertTrue(legacy.next());
                assertFalse(legacy.getString(1).contains("maxPositionPercent"));
                assertEquals("sha256:84a8aa1db1ec89acd824d270df6df53f652f58ad75a7688f59efb20bf86b4301",
                        legacy.getString(2));
            }
            try (var features = statement.executeQuery("""
                    SELECT count(*) FROM market_data.feature_definitions
                    WHERE element_catalog_version_id = '%s'
                    """.formatted(V2))) {
                assertTrue(features.next());
                assertEquals(4, features.getInt(1));
            }
        }
    }
}

package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
class TransactionalOutboxUpgradeMigrationIntegrationTest {
    private static final String MIGRATION = "V20260802231100__backend_transactional_outbox.sql";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @TempDir Path temporaryDirectory;

    @Test
    void backfillsLegacyPendingAndPublishedEnvelopesWithoutChangingPayload() throws Exception {
        Path central = Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        Path beforeA17 = Files.createDirectories(temporaryDirectory.resolve("before-a17"));
        try (var files = Files.list(central)) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                if (!MIGRATION.equals(source.getFileName().toString())) {
                    Files.copy(source, beforeA17.resolve(source.getFileName()));
                }
            }
        }
        migrate(beforeA17);
        UUID pending = insertLegacy("legacy-pending", false);
        UUID published = insertLegacy("legacy-published", true);

        migrate(central);

        assertEquals("PENDING", scalar("select delivery_status::text from operations.outbox_messages where id = ?", pending));
        assertEquals("PUBLISHED", scalar("select delivery_status::text from operations.outbox_messages where id = ?", published));
        assertEquals("legacy-pending", scalar("select producer_idempotency_key from operations.outbox_messages where id = ?", pending));
        assertNotNull(scalar("select payload_hash from operations.outbox_messages where id = ?", pending));
        assertEquals("{\"legacy\": true}", scalar("select payload_document::text from operations.outbox_messages where id = ?", pending));
        assertEquals("operations.outbox_delivery_attempts", scalar("select to_regclass('operations.outbox_delivery_attempts')::text", null));
        assertEquals("operations.outbox_consumer_receipts", scalar("select to_regclass('operations.outbox_consumer_receipts')::text", null));
    }

    private UUID insertLegacy(String key, boolean published) throws Exception {
        UUID id = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement("""
                     insert into operations.outbox_messages
                         (id, owner_domain, aggregate_id, event_type, event_schema_version,
                          payload_document, idempotency_key, published_at)
                     values (?, 'upgrade-test', ?, 'LEGACY_EVENT', '1.0.0',
                             '{"legacy":true}'::jsonb, ?,
                             case when ? then clock_timestamp() else null end)
                     """)) {
            statement.setObject(1, id);
            statement.setObject(2, UUID.randomUUID());
            statement.setString(3, key);
            statement.setBoolean(4, published);
            statement.executeUpdate();
        }
        return id;
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

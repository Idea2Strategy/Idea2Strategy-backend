package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class AccountLifecycleMigrationIntegrationTest {

    private static final UUID ACTIVE_ACCOUNT = UUID.fromString("12000000-0000-4000-8000-000000000001");
    private static final UUID CLOSING_ACCOUNT = UUID.fromString("12000000-0000-4000-8000-000000000002");
    private static final UUID LEGACY_EVENT = UUID.fromString("12000000-0000-4000-8000-000000000003");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @TempDir
    Path temporaryDirectory;

    @Test
    void backfillsLegacyAccountsAndEnforcesLifecycleEvidence() throws Exception {
        var centralDirectory = Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        var baselineDirectory = Files.createDirectories(temporaryDirectory.resolve("baseline"));
        Files.copy(
                centralDirectory.resolve(MigrationPolicy.BASELINE_FILE),
                baselineDirectory.resolve(MigrationPolicy.BASELINE_FILE));

        flyway(baselineDirectory).migrate();
        seedLegacyRows();

        var bundle = CanonicalMigrationBundleAssembler.assemble(
                centralDirectory, List.of(), temporaryDirectory.resolve("bundle"));
        assertTrue(flyway(bundle.directory()).migrate().migrationsExecuted > 0);

        assertEquals(List.of("PENDING_VERIFICATION", "ACTIVE", "DORMANT", "CLOSING", "CLOSED"), lifecycleStatuses());
        assertProjection(ACTIVE_ACCOUNT, "ACTIVE", 1, null);
        assertProjection(CLOSING_ACCOUNT, "CLOSING", 2, OffsetDateTime.parse("2026-09-01T00:00:00Z"));
        assertEquals(0, scalarInt("select count(*) from identity.account_retention_policy_versions"));
        assertEquals(1, scalarInt("select count(*) from identity.account_retention_policy_proposals"));

        var newAccount = UUID.fromString("12000000-0000-4000-8000-000000000004");
        execute("insert into identity.accounts (id, lifecycle_status, status_changed_at, created_at) values "
                + "('" + newAccount + "', 'PENDING_VERIFICATION', now(), now())");
        assertProjection(newAccount, "PENDING_VERIFICATION", 1, null);
        assertEquals(1, scalarInt("select count(*) from identity.account_lifecycle_events where account_id = '"
                + newAccount + "' and command_type = 'ACCOUNT_CREATED'"));

        assertInvalidChainedEvent(newAccount, 3, "PENDING_VERIFICATION");
        assertInvalidChainedEvent(newAccount, 2, "DORMANT");
        assertImmutableCommandReceipt(newAccount);

        assertThrows(SQLException.class, () -> execute(
                "update identity.account_lifecycle_events set reason_code = 'tampered' where id = '" + LEGACY_EVENT + "'"));
        assertThrows(SQLException.class, () -> execute(
                "insert into identity.account_identifier_quarantines "
                        + "(account_id, lifecycle_event_id, identifier_kind, provider_code, identifier_fingerprint, fingerprint_key_version, quarantined_at, reuse_eligible_at) "
                        + "select account_id, id, 'EMAIL', 'PASSWORD', 'fingerprint', 1, now(), now() + interval '29 days' "
                        + "from identity.account_lifecycle_events where account_id = '" + CLOSING_ACCOUNT + "' order by event_sequence desc limit 1"));
    }

    private void assertImmutableCommandReceipt(UUID accountId) throws Exception {
        execute("insert into identity.account_lifecycle_command_receipts "
                + "(account_id, command_type, idempotency_key, request_hash, response_status, response_code, "
                + "response_document, lifecycle_event_id, completed_at) "
                + "select account.id, 'ACCOUNT_CREATED', 'receipt-1', '" + "b".repeat(64) + "', 201, "
                + "'ACCOUNT_CREATED', '{\"status\":\"PENDING_VERIFICATION\"}'::jsonb, "
                + "account.last_lifecycle_event_id, now() from identity.accounts account where account.id = '"
                + accountId + "'");
        assertEquals(1, scalarInt("select count(*) from identity.account_lifecycle_command_receipts "
                + "where account_id = '" + accountId + "' and command_type = 'ACCOUNT_CREATED' "
                + "and idempotency_key = 'receipt-1' and response_status = 201"));
        assertThrows(SQLException.class, () -> execute(
                "update identity.account_lifecycle_command_receipts set response_status = 200 "
                        + "where account_id = '" + accountId + "' and command_type = 'ACCOUNT_CREATED' "
                        + "and idempotency_key = 'receipt-1'"));
    }

    private void assertInvalidChainedEvent(UUID accountId, long sequence, String previousStatus) throws Exception {
        var eventId = UUID.randomUUID();
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            UUID predecessorId;
            try (var statement = connection.prepareStatement(
                            "select last_lifecycle_event_id from identity.accounts where id = ?")) {
                statement.setObject(1, accountId);
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                    predecessorId = result.getObject(1, UUID.class);
                }
            }
            try (var statement = connection.prepareStatement(
                    "insert into identity.account_lifecycle_events "
                            + "(id, account_id, event_sequence, previous_event_id, lifecycle_version, previous_status, "
                            + "new_status, command_type, actor_type, correlation_id, idempotency_key, request_hash) "
                            + "values (?, ?, ?, ?, ?, ?::identity.account_lifecycle_status, 'ACTIVE', "
                            + "'ACCOUNT_ACTIVATED', 'SYSTEM', ?, ?, ?)")) {
                statement.setObject(1, eventId);
                statement.setObject(2, accountId);
                statement.setLong(3, sequence);
                statement.setObject(4, predecessorId);
                statement.setLong(5, sequence);
                statement.setString(6, previousStatus);
                statement.setObject(7, UUID.randomUUID());
                statement.setString(8, "invalid-chain:" + eventId);
                statement.setString(9, "a".repeat(64));
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "update identity.accounts set lifecycle_status = 'ACTIVE', lifecycle_version = ?, "
                            + "last_lifecycle_event_id = ?, status_changed_at = now() where id = ?")) {
                statement.setLong(1, sequence);
                statement.setObject(2, eventId);
                statement.setObject(3, accountId);
                statement.executeUpdate();
            }
            assertThrows(SQLException.class, connection::commit);
            connection.rollback();
        }
    }

    private void seedLegacyRows() throws Exception {
        execute("insert into identity.accounts (id, lifecycle_status, status_changed_at, created_at) values "
                + "('" + ACTIVE_ACCOUNT + "', 'ACTIVE', '2026-07-01T00:00:00Z', '2026-07-01T00:00:00Z'), "
                + "('" + CLOSING_ACCOUNT + "', 'CLOSING', '2026-08-02T00:00:00Z', '2026-07-01T00:00:00Z')");
        execute("insert into identity.account_lifecycle_events "
                + "(id, account_id, event_sequence, previous_status, new_status, reason_code, occurred_at) values "
                + "('" + LEGACY_EVENT + "', '" + CLOSING_ACCOUNT + "', 1, null, 'ACTIVE', 'LEGACY', '2026-07-01T00:00:00Z')");
    }

    private List<String> lifecycleStatuses() throws Exception {
        try (var connection = connection();
                var statement = connection.prepareStatement(
                        "select value.enumlabel from pg_type type "
                                + "join pg_namespace namespace on namespace.oid = type.typnamespace "
                                + "join pg_enum value on value.enumtypid = type.oid "
                                + "where namespace.nspname = 'identity' and type.typname = 'account_lifecycle_status' "
                                + "order by value.enumsortorder");
                var result = statement.executeQuery()) {
            var values = new java.util.ArrayList<String>();
            while (result.next()) values.add(result.getString(1));
            return values;
        }
    }

    private void assertProjection(UUID accountId, String status, long version, OffsetDateTime deadline) throws Exception {
        try (var connection = connection();
                var statement = connection.prepareStatement(
                        "select lifecycle_status::text, lifecycle_version, last_lifecycle_event_id, cancellation_deadline_at "
                                + "from identity.accounts where id = ?")) {
            statement.setObject(1, accountId);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(status, result.getString(1));
                assertEquals(version, result.getLong(2));
                assertTrue(result.getObject(3) != null);
                if (deadline == null) assertEquals(null, result.getObject(4));
                else assertEquals(deadline.toInstant(), result.getObject(4, OffsetDateTime.class).toInstant());
            }
        }
    }

    private int scalarInt(String sql) throws Exception {
        try (var connection = connection();
                var statement = connection.prepareStatement(sql);
                var result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private void execute(String sql) throws Exception {
        try (var connection = connection(); var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private java.sql.Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Flyway flyway(Path directory) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + directory)
                .load();
    }
}

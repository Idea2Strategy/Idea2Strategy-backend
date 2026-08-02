package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class AccountRetentionUpgradeMigrationIntegrationTest {
    private static final Set<String> RETENTION_MIGRATIONS = Set.of(
            "V20260802220000__backend_retention_category_split.sql",
            "V20260802220100__trading_private_bot_runtime_cleanup.sql",
            "V20260802220200__backend_retention_execution.sql",
            "V20260802220300__backtest_competition_owner_anonymization.sql");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @TempDir Path temporaryDirectory;

    @Test
    void upgradesMergedDevelopAndBackfillsOnlyEligibleHistoricalClosures() throws Exception {
        Path central = Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        Path before132 = Files.createDirectories(temporaryDirectory.resolve("before-132"));
        try (var files = Files.list(central)) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                if (!RETENTION_MIGRATIONS.contains(source.getFileName().toString())) {
                    Files.copy(source, before132.resolve(source.getFileName()));
                }
            }
        }
        migrate(before132);
        UUID beforeEffective = seedHistoricalClosed(OffsetDateTime.parse("2026-08-02T10:00:00Z"));
        UUID afterEffective = seedHistoricalClosed(OffsetDateTime.parse("2026-08-02T11:00:00Z"));

        migrate(central);

        assertEquals(8, scalar("""
                select count(*) from identity.account_retention_obligations
                where account_id = ? and status = 'FAILED'
                  and failure_code = 'RETENTION_POLICY_MISSING'
                """, beforeEffective));
        assertEquals(10, scalar("""
                select count(*) from identity.account_retention_obligations
                where account_id = ? and status = 'PENDING'
                  and retention_policy_version = 'A12-2026-08-02'
                """, afterEffective));
        assertEquals(2, scalar("""
                select count(*) from identity.account_retention_obligations
                where account_id = ? and data_category in (
                    'BOT_STRATEGY_PRIVATE_DATA', 'COMPETITION_RESULT_EVIDENCE')
                """, afterEffective));
        assertEquals(4, scalar("select count(*) from flyway_schema_history where version >= '20260802220000'"));
    }

    private UUID seedHistoricalClosed(OffsetDateTime closedAt) throws Exception {
        UUID accountId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement(
                    "insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')")) {
                insert.setObject(1, accountId);
                insert.executeUpdate();
            }
            UUID previousEvent;
            try (var query = connection.prepareStatement(
                    "select last_lifecycle_event_id from identity.accounts where id = ?")) {
                query.setObject(1, accountId);
                try (var result = query.executeQuery()) {
                    result.next();
                    previousEvent = result.getObject(1, UUID.class);
                }
            }
            UUID closeEvent = UUID.randomUUID();
            try (var insert = connection.prepareStatement("""
                    insert into identity.account_lifecycle_events
                        (id, account_id, event_sequence, previous_event_id, lifecycle_version,
                         previous_status, new_status, command_type, actor_type, correlation_id,
                         idempotency_key, request_hash, reason_code, occurred_at)
                    values (?, ?, 2, ?, 2, 'ACTIVE', 'CLOSED', 'ACCOUNT_CLOSED', 'SYSTEM', ?,
                            ?, ?, 'WITHDRAWAL_COMPLETED', ?)
                    """)) {
                insert.setObject(1, closeEvent);
                insert.setObject(2, accountId);
                insert.setObject(3, previousEvent);
                insert.setObject(4, UUID.randomUUID());
                insert.setString(5, "upgrade-close:" + accountId);
                insert.setString(6, "upgrade-close-hash:" + accountId);
                insert.setObject(7, closedAt);
                insert.executeUpdate();
            }
            try (var update = connection.prepareStatement("""
                    update identity.accounts
                    set lifecycle_status = 'CLOSED', status_changed_at = ?, closed_at = ?,
                        lifecycle_version = 2, last_lifecycle_event_id = ?
                    where id = ?
                    """)) {
                update.setObject(1, closedAt);
                update.setObject(2, closedAt);
                update.setObject(3, closeEvent);
                update.setObject(4, accountId);
                update.executeUpdate();
            }
            try (var insert = connection.prepareStatement("""
                    insert into identity.account_retention_obligations
                        (account_id, lifecycle_event_id, data_category, status, failure_code)
                    select ?, ?, category, 'FAILED', 'RETENTION_POLICY_MISSING'
                    from unnest(enum_range(NULL::identity.account_data_category)) category
                    """)) {
                insert.setObject(1, accountId);
                insert.setObject(2, closeEvent);
                insert.executeUpdate();
            }
            connection.commit();
        }
        return accountId;
    }

    private int scalar(String sql, UUID accountId) throws Exception {
        try (var connection = connection(); var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, accountId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private int scalar(String sql) throws Exception {
        try (var connection = connection(); var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private void migrate(Path directory) {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + directory.toAbsolutePath()).load().migrate();
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}

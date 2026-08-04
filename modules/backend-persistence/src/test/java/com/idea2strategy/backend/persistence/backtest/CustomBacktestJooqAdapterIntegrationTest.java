package com.idea2strategy.backend.persistence.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.backtest.BacktestRequestIdempotencyConflictException;
import com.idea2strategy.backend.application.backtest.CustomBacktestCommand;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = CustomBacktestJooqAdapterIntegrationTest.TestApplication.class)
class CustomBacktestJooqAdapterIntegrationTest {
    private static final UUID ACCOUNT = id(1);
    private static final UUID BOT = id(2);
    private static final UUID PROVIDER = id(3);
    private static final UUID FEED = id(4);
    private static final UUID DATASET = id(5);
    private static final UUID FEE = id(6);
    private static final UUID BUFFER = id(7);
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired CustomBacktestJooqAdapter adapter;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        var at = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", ACCOUNT);
        jdbc.update(
                "insert into market_data.providers "
                        + "(id, code, display_name, rights_version, status, created_at) "
                        + "values (?, 'CUSTOM_TEST', 'Custom Test', 'v1', 'ACTIVE', ?)",
                PROVIDER, at);
        jdbc.update(
                "insert into market_data.feeds "
                        + "(id, provider_id, code, data_kind, resolution, timezone_name, feed_version, created_at) "
                        + "values (?, ?, 'CUSTOM_TEST', 'BAR', '1d', 'UTC', 'v1', ?)",
                FEED, PROVIDER, at);
        jdbc.update(
                "insert into market_data.dataset_manifests "
                        + "(id, feed_id, data_layer, resolution, revision_number, status, period_start, period_end, "
                        + "schema_version, dataset_hash, created_at, available_at) "
                        + "values (?, ?, 'ADJUSTED', '1d', 1, 'AVAILABLE', '2024-01-01T00:00:00Z', "
                        + "'2024-12-31T23:59:59Z', 'v1', ?, ?, ?)",
                DATASET, FEED, "a".repeat(64), at, at);
        jdbc.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'CUSTOM_TEST', 'v1', 20, 'v1', ?, ?, ?)",
                FEE, "b".repeat(64), at.minusDays(1), at.minusDays(1));
        jdbc.update(
                "insert into trading.buying_power_buffer_policy_versions "
                        + "(id, policy_code, version, buffer_bps, rounding_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'CUSTOM_TEST', 'v1', 100, 'v1', ?, ?, ?)",
                BUFFER, "c".repeat(64), at.minusDays(1), at.minusDays(1));
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, created_at, "
                        + "execution_eligible_from, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', 'Custom Bot', 'RUNNING', ?, ?, ?, 0, ?)",
                BOT, ACCOUNT, at, at, at, at);
        jdbc.update(
                "insert into bot.launch_snapshots "
                        + "(bot_id, snapshot_schema_version, semantic_snapshot, presentation_snapshot, semantic_hash, "
                        + "presentation_hash, snapshot_hash, created_at) "
                        + "values (?, 'basic-launch-snapshot.v1', '{}'::jsonb, '{}'::jsonb, ?, ?, ?, ?)",
                BOT, "d".repeat(64), "e".repeat(64), "f".repeat(64), at);
        jdbc.update(
                "insert into bot.launch_contract_plans "
                        + "(bot_id, contract_version, plan_schema_version, plan_checksum, plan_document, created_at) "
                        + "values (?, 'strategy-bot.v1', 'basic-compiled-plan.v1', ?, "
                        + "'{\"instrumentCatalogVersion\":\"us-supported-universe:2026-08-04\"}'::jsonb, ?)",
                BOT, "sha256:" + "1".repeat(64), at);
        jdbc.update(
                "insert into bot.launch_configurations "
                        + "(bot_id, initial_cash_amount, currency_code, broker_rules_version, accounting_rules_version, "
                        + "precision_rules_version, fee_policy_id, slippage_rate_bps, buying_power_buffer_policy_id, "
                        + "candidate_conflict_policy, configuration_hash) "
                        + "values (?, 100000, 'USD', 'v1', 'v1', 'v1', ?, 5, ?, '{}'::jsonb, ?)",
                BOT, FEE, BUFFER, "2".repeat(64));
    }

    @Test
    void queuesOnceAndRejectsAnIdempotencyKeyReusedForAnotherPeriod() {
        var command = command(LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"));
        var created = adapter.enqueue(ACCOUNT, command, NOW);
        var duplicate = adapter.enqueue(ACCOUNT, command, NOW.plusSeconds(30));

        assertThat(created.created()).isTrue();
        assertThat(duplicate.created()).isFalse();
        assertThat(duplicate.messageId()).isEqualTo(created.messageId());
        assertThat(jdbc.queryForObject(
                        "select count(*) from operations.outbox_messages "
                                + "where event_type = 'CUSTOM_BACKTEST_REQUESTED'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForMap(
                        "select producer_idempotency_key, payload_hash, "
                                + "payload_document ->> 'requestReason' as reason, "
                                + "payload_document ->> 'requestingAccountId' as requesting_account_id, "
                                + "payload_document ->> 'expectedDatasetHash' as expected_dataset_hash, "
                                + "payload_document ->> 'instrumentCatalogVersion' as instrument_catalog_version, "
                                + "payload_document ->> 'initialCashAmount' as initial_cash_amount "
                                + "from operations.outbox_messages where id = ?",
                        created.messageId()))
                .satisfies(row -> {
                    assertThat(row.get("producer_idempotency_key")).asString().matches("sha256:[0-9a-f]{64}");
                    assertThat(row.get("payload_hash")).asString().matches("[0-9a-f]{64}");
                    assertThat(row.get("reason")).isEqualTo("USER_PERIOD");
                    assertThat(row.get("requesting_account_id")).isEqualTo(ACCOUNT.toString());
                    assertThat(row.get("expected_dataset_hash")).isEqualTo("sha256:" + "a".repeat(64));
                    assertThat(row.get("instrument_catalog_version"))
                            .isEqualTo("us-supported-universe:2026-08-04");
                    assertThat(row.get("initial_cash_amount")).isEqualTo("100000.00000000");
                });

        assertThatThrownBy(() -> adapter.enqueue(
                        ACCOUNT,
                        command(LocalDate.parse("2024-02-01"), LocalDate.parse("2024-11-30")),
                        NOW.plusSeconds(60)))
                .isInstanceOf(BacktestRequestIdempotencyConflictException.class);
    }

    private static CustomBacktestCommand command(LocalDate start, LocalDate end) {
        return new CustomBacktestCommand(BOT, DATASET, start, end, "custom-key-1");
    }

    private static UUID id(int suffix) {
        return UUID.fromString("99000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({BacktestRequestOutboxStore.class, CustomBacktestJooqAdapter.class})
    static class TestApplication {}
}

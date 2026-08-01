package com.idea2strategy.backend.persistence.botcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
@SpringBootTest(classes = BotExecutionPreflightPersistenceIntegrationTest.TestApplication.class)
class BotExecutionPreflightPersistenceIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000021");
    private static final UUID BOT_ID = UUID.fromString("20000000-0000-4000-8000-000000000021");
    private static final UUID PARTITION_ID = UUID.fromString("30000000-0000-4000-8000-000000000021");
    private static final UUID FLOW_ID = UUID.fromString("40000000-0000-4000-8000-000000000021");
    private static final UUID CATALOG_ID = UUID.fromString("50000000-0000-4000-8000-000000000021");
    private static final UUID PLAN_ID = UUID.fromString("60000000-0000-4000-8000-000000000021");
    private static final UUID INSTRUMENT_ID = UUID.fromString("70000000-0000-4000-8000-000000000021");
    private static final UUID FEATURE_ID = UUID.fromString("80000000-0000-4000-8000-000000000021");
    private static final UUID PROVIDER_ID = UUID.fromString("90000000-0000-4000-8000-000000000021");
    private static final UUID FEED_ID = UUID.fromString("a0000000-0000-4000-8000-000000000021");
    private static final UUID FEE_ID = UUID.fromString("b0000000-0000-4000-8000-000000000021");
    private static final UUID BUFFER_ID = UUID.fromString("c0000000-0000-4000-8000-000000000021");
    private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

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

    @Autowired
    private BotExecutionPreflightJooqQueryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareReadyBot() {
        var at = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", OWNER_ID);
        jdbc.update(
                "insert into strategy.element_catalog_versions "
                        + "(id, language_version, schema_version, catalog_version, data_requirement_version, "
                        + "definition_hash, published_at) values (?, 'basic/v1', 'schema/v1', 'catalog/v1', "
                        + "'data/v1', ?, ?)",
                CATALOG_ID, HASH_A, at);
        jdbc.update(
                "insert into strategy.compiled_flow_plans "
                        + "(id, element_catalog_version_id, semantic_hash, compiler_version, "
                        + "required_feature_set_hash, plan_document, plan_hash, created_at) "
                        + "values (?, ?, ?, 'compiler/v1', ?, '{}'::jsonb, ?, ?)",
                PLAN_ID, CATALOG_ID, HASH_A, HASH_B, HASH_A, at);
        jdbc.update(
                "insert into market_data.instruments "
                        + "(id, asset_type, primary_exchange_mic, currency_code) values (?, 'STOCK', 'XNAS', 'USD')",
                INSTRUMENT_ID);
        jdbc.update(
                "insert into market_data.instrument_symbols "
                        + "(instrument_id, exchange_mic, symbol, effective_from) values (?, 'XNAS', 'AAPL', ?)",
                INSTRUMENT_ID, at.minusDays(1));
        jdbc.update(
                "insert into market_data.feature_definitions "
                        + "(id, element_catalog_version_id, feature_code, calculator_version, resolution, "
                        + "normalized_parameters, output_value_type, required_history_points, definition_hash) "
                        + "values (?, ?, 'RSI_14', '1.0.0', '1m', '{}'::jsonb, 'NUMBER', 14, ?)",
                FEATURE_ID, CATALOG_ID, HASH_B);
        jdbc.update(
                "insert into market_data.providers "
                        + "(id, code, display_name, rights_version, status, created_at) "
                        + "values (?, 'TEST', 'Test provider', 'rights/v1', 'ACTIVE', ?)",
                PROVIDER_ID, at);
        jdbc.update(
                "insert into market_data.feeds "
                        + "(id, provider_id, code, data_kind, resolution, timezone_name, feed_version, created_at) "
                        + "values (?, ?, 'SIP', 'BAR', '1m', 'America/New_York', 'v1', ?)",
                FEED_ID, PROVIDER_ID, at);
        jdbc.update(
                "insert into market_data.stream_watermarks "
                        + "(feed_id, last_source_event_at, last_ingested_at, last_sequence, updated_at) "
                        + "values (?, ?, ?, 1, ?)",
                FEED_ID, at.minusSeconds(1), at.minusSeconds(1), at);
        jdbc.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'OFFICIAL', '1', 20, '1', ?, ?, ?)",
                FEE_ID, HASH_A, at.minusDays(1), at.minusDays(1));
        jdbc.update(
                "insert into trading.buying_power_buffer_policy_versions "
                        + "(id, policy_code, version, buffer_bps, rounding_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'OFFICIAL', '1', 100, '1', ?, ?, ?)",
                BUFFER_ID, HASH_B, at.minusDays(1), at.minusDays(1));
        insertBot(BOT_ID, "Ready bot");
        jdbc.update(
                "insert into bot.launch_configurations "
                        + "(bot_id, initial_cash_amount, currency_code, broker_rules_version, accounting_rules_version, "
                        + "precision_rules_version, fee_policy_id, slippage_rate_bps, buying_power_buffer_policy_id, "
                        + "candidate_conflict_policy, configuration_hash) "
                        + "values (?, 100000, 'USD', 'broker/v1', 'accounting/v1', 'precision/v1', ?, 5, ?, "
                        + "'{\"policy\":\"NET\"}'::jsonb, ?)",
                BOT_ID, FEE_ID, BUFFER_ID, HASH_A);
        jdbc.update(
                "insert into bot.bot_partitions "
                        + "(id, bot_id, name, budget_cap_bps, position_x, position_y, configuration_hash, "
                        + "edit_sequence, created_at, updated_at) values (?, ?, 'Main', 10000, 0, 0, ?, 0, ?, ?)",
                PARTITION_ID, BOT_ID, HASH_A, at, at);
        jdbc.update(
                "insert into bot.flows "
                        + "(id, partition_id, name, element_catalog_version_id, compiled_flow_plan_id, position_x, "
                        + "position_y, semantic_document, layout_document, layout_schema_version, semantic_hash, "
                        + "layout_hash, configuration_hash, edit_sequence, created_at, updated_at) "
                        + "values (?, ?, 'Flow', ?, ?, 0, 0, '{}'::jsonb, '{}'::jsonb, 'layout/v1', ?, ?, ?, 0, ?, ?)",
                FLOW_ID, PARTITION_ID, CATALOG_ID, PLAN_ID, HASH_A, HASH_B, HASH_A, at, at);
        jdbc.update("insert into bot.flow_instruments (flow_id, instrument_id) values (?, ?)",
                FLOW_ID, INSTRUMENT_ID);
        jdbc.update(
                "insert into bot.flow_feature_requirements "
                        + "(flow_id, instrument_id, feature_definition_id) values (?, ?, ?)",
                FLOW_ID, INSTRUMENT_ID, FEATURE_ID);
    }

    @Test
    void readsReadyFactsThenSurfacesEveryDatabaseBackedBlocker() {
        var ready = adapter.findOwnedById(BOT_ID, OWNER_ID, NOW).orElseThrow();

        assertThat(ready.projectedConcurrentExecutionCount()).isEqualTo(1);
        assertThat(ready.unsupportedInstrumentIds()).isEmpty();
        assertThat(ready.feePolicyActive()).isTrue();
        assertThat(ready.buyingPowerBufferPolicyActive()).isTrue();
        assertThat(ready.riskPolicyConfigured()).isTrue();
        assertThat(ready.unavailableDataRequirements()).isEmpty();

        for (int index = 0; index < 10; index++) {
            insertBot(UUID.nameUUIDFromBytes(("extra-" + index).getBytes()), "Extra " + index);
        }
        var at = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update("update market_data.instrument_symbols set effective_to = ? where instrument_id = ?", at, INSTRUMENT_ID);
        jdbc.update("update market_data.providers set status = 'INACTIVE' where id = ?", PROVIDER_ID);
        jdbc.update("update trading.fee_policy_versions set effective_to = ? where id = ?", at, FEE_ID);
        jdbc.update("update trading.buying_power_buffer_policy_versions set effective_to = ? where id = ?", at, BUFFER_ID);
        jdbc.update("update bot.launch_configurations set candidate_conflict_policy = '{}'::jsonb where bot_id = ?", BOT_ID);

        var blocked = adapter.findOwnedById(BOT_ID, OWNER_ID, NOW).orElseThrow();

        assertThat(blocked.projectedConcurrentExecutionCount()).isEqualTo(11);
        assertThat(blocked.unsupportedInstrumentIds()).containsExactly(INSTRUMENT_ID);
        assertThat(blocked.feePolicyActive()).isFalse();
        assertThat(blocked.buyingPowerBufferPolicyActive()).isFalse();
        assertThat(blocked.riskPolicyConfigured()).isFalse();
        assertThat(blocked.unavailableDataRequirements()).containsExactly(
                new com.idea2strategy.backend.application.botcontrol.BotExecutionPreflightFacts.DataRequirement(
                        INSTRUMENT_ID, FEATURE_ID));
        assertThat(adapter.findOwnedById(BOT_ID, UUID.randomUUID(), NOW)).isEmpty();
    }

    private void insertBot(UUID botId, String name) {
        var at = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, created_at, "
                        + "execution_eligible_from, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', ?, 'RUNNING', ?, ?, ?, 0, ?)",
                botId, OWNER_ID, name, at, at, at, at);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(BotExecutionPreflightJooqQueryAdapter.class)
    static class TestApplication {}
}

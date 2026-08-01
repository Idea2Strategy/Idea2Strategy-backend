package com.idea2strategy.backend.persistence.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseRejectedException;
import com.idea2strategy.backend.application.strategy.OfficialBacktestRequest;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease;
import com.idea2strategy.backend.persistence.competition.RoomStrategyBotProvisioningJooqAdapter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = ImmutableStrategyReleasePersistenceIntegrationTest.TestApplication.class)
class ImmutableStrategyReleasePersistenceIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000011");
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000011");
    private static final UUID RUN_ID = UUID.fromString("30000000-0000-4000-8000-000000000011");
    private static final UUID CATALOG_ID = UUID.fromString("40000000-0000-4000-8000-000000000011");
    private static final UUID PLAN_ID = UUID.fromString("50000000-0000-4000-8000-000000000011");
    private static final UUID BOT_ID = UUID.fromString("60000000-0000-4000-8000-000000000011");
    private static final UUID PARTITION_ID = UUID.fromString("70000000-0000-4000-8000-000000000011");
    private static final UUID FLOW_ID = UUID.fromString("80000000-0000-4000-8000-000000000011");
    private static final UUID INSTRUMENT_ID = UUID.fromString("90000000-0000-4000-8000-000000000011");
    private static final UUID FEATURE_ID = UUID.fromString("a0000000-0000-4000-8000-000000000011");
    private static final UUID FEE_ID = UUID.fromString("b0000000-0000-4000-8000-000000000011");
    private static final UUID BUFFER_ID = UUID.fromString("c0000000-0000-4000-8000-000000000011");
    private static final UUID DATASET_ID = UUID.fromString("d0000000-0000-4000-8000-000000000011");
    private static final UUID FEED_ID = UUID.fromString("e0000000-0000-4000-8000-000000000011");
    private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final String HASH_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";

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
    private ImmutableStrategyReleaseJooqCommandAdapter adapter;

    @Autowired
    private RoomStrategyBotProvisioningJooqAdapter roomAdapter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareValidatedStrategyAndPinnedPolicies() {
        var at = NOW.atOffset(ZoneOffset.UTC);
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", OWNER_ID);
        jdbc.update(
                "insert into strategy.element_catalog_versions "
                        + "(id, language_version, schema_version, catalog_version, data_requirement_version, "
                        + "definition_hash, published_at) values (?, 'basic/v1', 'schema/v1', 'catalog/v1', "
                        + "'data/v1', ?, ?)",
                CATALOG_ID, HASH_D, at);
        jdbc.update(
                "insert into strategy.compiled_flow_plans "
                        + "(id, element_catalog_version_id, semantic_hash, compiler_version, required_feature_set_hash, "
                        + "plan_document, plan_hash, created_at) values (?, ?, ?, 'basic-compiler:1.0.0', ?, "
                        + "'{}'::jsonb, ?, ?)",
                PLAN_ID, CATALOG_ID, HASH_A, HASH_B, HASH_C, at);
        jdbc.update(
                "insert into market_data.providers (id, code, display_name, rights_version, status, created_at) "
                        + "values (?, 'TEST', 'Test', 'rights/v1', 'ACTIVE', ?)",
                UUID.fromString("f0000000-0000-4000-8000-000000000011"), at);
        jdbc.update(
                "insert into market_data.feeds "
                        + "(id, provider_id, code, data_kind, resolution, timezone_name, feed_version, created_at) "
                        + "values (?, ?, 'OFFICIAL', 'BAR', '1d', 'UTC', 'v1', ?)",
                FEED_ID, UUID.fromString("f0000000-0000-4000-8000-000000000011"), at);
        jdbc.update(
                "insert into market_data.dataset_manifests "
                        + "(id, feed_id, data_layer, resolution, revision_number, status, period_start, period_end, "
                        + "schema_version, dataset_hash, created_at, available_at) "
                        + "values (?, ?, 'ADJUSTED', '1d', 1, 'AVAILABLE', '2025-01-01T00:00:00Z', "
                        + "'2025-12-31T00:00:00Z', 'v1', ?, ?, ?)",
                DATASET_ID, FEED_ID, HASH_D, at, at);
        jdbc.update(
                "insert into market_data.instruments "
                        + "(id, asset_type, primary_exchange_mic, currency_code) values (?, 'STOCK', 'XNAS', 'USD')",
                INSTRUMENT_ID);
        jdbc.update(
                "insert into market_data.feature_definitions "
                        + "(id, element_catalog_version_id, feature_code, calculator_version, resolution, "
                        + "normalized_parameters, output_value_type, required_history_points, definition_hash) "
                        + "values (?, ?, 'RSI_14', '1.0.0', '1m', '{}'::jsonb, 'NUMBER', 14, ?)",
                FEATURE_ID, CATALOG_ID, HASH_B);
        jdbc.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'OFFICIAL', '1', 20, '1', ?, ?, ?)",
                FEE_ID, HASH_A, at, at);
        jdbc.update(
                "insert into trading.buying_power_buffer_policy_versions "
                        + "(id, policy_code, version, buffer_bps, rounding_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'OFFICIAL', '1', 100, '1', ?, ?, ?)",
                BUFFER_ID, HASH_C, at, at);
        jdbc.update(
                "insert into strategy.strategies "
                        + "(id, owner_account_id, mode, name, edit_sequence, created_at, updated_at) "
                        + "values (?, ?, 'BASIC', 'Momentum', 7, ?, ?)",
                STRATEGY_ID, OWNER_ID, at, at);
        jdbc.update(
                "insert into strategy.strategy_documents "
                        + "(strategy_id, semantic_document, presentation_document, semantic_schema_version, "
                        + "presentation_schema_version, semantic_hash, presentation_hash, edit_sequence, "
                        + "created_at, updated_at) values (?, '{}'::jsonb, '{}'::jsonb, 'basic-semantic/v1', "
                        + "'basic-presentation/v1', ?, ?, 7, ?, ?)",
                STRATEGY_ID, HASH_A, HASH_B, at, at);
        jdbc.update(
                "insert into strategy.validation_runs "
                        + "(id, strategy_id, requested_by_account_id, requested_edit_sequence, semantic_hash, "
                        + "element_catalog_version_id, status, issue_count, result_document, requested_at, completed_at) "
                        + "values (?, ?, ?, 7, ?, ?, 'VALID', 0, '{}'::jsonb, ?, ?)",
                RUN_ID, STRATEGY_ID, OWNER_ID, HASH_A, CATALOG_ID, at, at);
    }

    @Test
    void atomicallyCreatesOneImmutableAggregateAndMakesTheReleaseIdIdempotent() {
        ImmutableStrategyRelease release = release(BOT_ID, HASH_D);
        OfficialBacktestRequest request = OfficialBacktestRequest.forRelease(
                release, HASH_C, DATASET_ID);

        assertThat(adapter.saveOnce(release, request, RUN_ID, 7, HASH_A)).isEqualTo(release);
        assertThat(adapter.saveOnce(release, request, RUN_ID, 7, HASH_A)).isEqualTo(release);

        assertThat(count("bot.bots")).isEqualTo(1);
        assertThat(count("bot.launch_snapshots")).isEqualTo(1);
        assertThat(count("bot.launch_configurations")).isEqualTo(1);
        assertThat(count("bot.bot_partitions")).isEqualTo(1);
        assertThat(count("bot.flows")).isEqualTo(1);
        assertThat(count("bot.flow_instruments")).isEqualTo(1);
        assertThat(count("bot.flow_feature_requirements")).isEqualTo(1);
        assertThat(count("backtest.runs")).isEqualTo(1);
        assertThat(count("operations.outbox_messages")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select status::text from backtest.runs where bot_id = ?", String.class, BOT_ID))
                .isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                        "select payload_document ->> 'datasetManifestId' from operations.outbox_messages "
                                + "where aggregate_id = ?", String.class, BOT_ID))
                .isEqualTo(DATASET_ID.toString());
        assertThat(jdbc.queryForObject(
                        "select started_at is null from bot.bots where id = ?", Boolean.class, BOT_ID))
                .isTrue();

        assertThatThrownBy(() -> adapter.saveOnce(
                        release(BOT_ID, HASH_C), request, RUN_ID, 7, HASH_A))
                .isInstanceOf(ImmutableStrategyReleaseRejectedException.class)
                .hasMessage("Release id is already bound to different immutable content");

        UUID roomBotId = UUID.fromString("60000000-0000-4000-8000-000000000012");
        Instant evaluationStart = NOW.plusSeconds(3600);
        var roomRelease = release(roomBotId, HASH_D);
        UUID provisioned = new TransactionTemplate(transactionManager).execute(status -> roomAdapter.provision(
                roomRelease, RUN_ID, 7, HASH_A, evaluationStart));

        assertThat(provisioned).isEqualTo(roomBotId);
        assertThat(count("bot.bots")).isEqualTo(2);
        assertThat(count("bot.launch_snapshots")).isEqualTo(2);
        assertThat(count("bot.launch_configurations")).isEqualTo(2);
        assertThat(count("bot.bot_partitions")).isEqualTo(2);
        assertThat(count("bot.flows")).isEqualTo(2);
        assertThat(count("backtest.runs")).isEqualTo(1);
        assertThat(count("operations.outbox_messages")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select execution_eligible_from from bot.bots where id = ?",
                        java.time.OffsetDateTime.class,
                        roomBotId).toInstant())
                .isEqualTo(evaluationStart);
        assertThat(jdbc.queryForObject(
                        "select started_at is null from bot.bots where id = ?", Boolean.class, roomBotId))
                .isTrue();
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static ImmutableStrategyRelease release(UUID botId, String snapshotHash) {
        var configuration = new ImmutableStrategyRelease.LaunchConfiguration(
                new BigDecimal("100000.00"), "broker/v1", "accounting/v1", "precision/v1",
                FEE_ID, BUFFER_ID, "{\"policy\":\"FIRST_WINS\"}", HASH_C);
        UUID flowId = UUID.nameUUIDFromBytes((botId + ":flow").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID partitionId = UUID.nameUUIDFromBytes((botId + ":partition").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var flow = new ImmutableStrategyRelease.Flow(
                flowId, "buy", CATALOG_ID, PLAN_ID, "{\"key\":\"buy\"}", "{}", HASH_A, HASH_B,
                HASH_C, List.of(INSTRUMENT_ID),
                List.of(new ImmutableStrategyRelease.FeatureRequirement(INSTRUMENT_ID, FEATURE_ID)), 0);
        var partition = new ImmutableStrategyRelease.Partition(
                partitionId, "Momentum", null, 10_000, HASH_C, List.of(flow));
        return new ImmutableStrategyRelease(
                botId, OWNER_ID, "Momentum", null, "{\"mode\":\"BASIC\"}", "{\"name\":\"Momentum\"}",
                HASH_A, HASH_B, snapshotHash, configuration, partition, NOW);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({ImmutableStrategyReleaseJooqCommandAdapter.class, RoomStrategyBotProvisioningJooqAdapter.class})
    static class TestApplication {}
}

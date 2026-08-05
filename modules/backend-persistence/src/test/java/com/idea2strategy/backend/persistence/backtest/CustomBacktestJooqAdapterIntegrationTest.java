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
import org.springframework.dao.InvalidDataAccessApiUsageException;
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
    private static final UUID CATALOG = id(8);
    private static final UUID INSTRUMENT = id(9);
    private static final UUID FEATURE = id(10);
    private static final UUID PIPELINE = id(11);
    private static final UUID FEATURE_MANIFEST = id(12);
    private static final UUID FEATURE_OBJECT = id(13);
    private static final UUID FEATURE_DATASET_OBJECT = id(14);
    private static final UUID MATERIALIZATION = id(15);
    private static final UUID FEATURE_FEED = id(16);
    private static final String POLICY = "backtest-policy-v1";
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
        jdbc.update("delete from operations.outbox_messages where event_type = 'CUSTOM_BACKTEST_REQUESTED'");
        jdbc.update("delete from backtest.run_attempts where run_id in (select id from backtest.runs where bot_id = ?)", BOT);
        jdbc.update("delete from backtest.input_datasets where input_bundle_id in "
                + "(select id from backtest.input_bundles where run_id in (select id from backtest.runs where bot_id = ?))", BOT);
        jdbc.update("delete from backtest.input_feature_materializations where input_bundle_id in "
                + "(select id from backtest.input_bundles where run_id in (select id from backtest.runs where bot_id = ?))", BOT);
        jdbc.update("delete from backtest.run_input_pins where run_id in (select id from backtest.runs where bot_id = ?)", BOT);
        jdbc.update("delete from backtest.input_bundles where run_id in (select id from backtest.runs where bot_id = ?)", BOT);
        jdbc.update("delete from backtest.runs where bot_id = ?", BOT);
        jdbc.update("delete from bot.launch_contract_plans where bot_id = ?", BOT);
        jdbc.update("delete from bot.launch_configurations where bot_id = ?", BOT);
        jdbc.update("delete from bot.launch_snapshots where bot_id = ?", BOT);
        jdbc.update("delete from bot.bots where id = ?", BOT);
        jdbc.update("delete from market_data.feature_materializations where id = ?", MATERIALIZATION);
        jdbc.update("delete from market_data.dataset_objects where id = ?", FEATURE_DATASET_OBJECT);
        jdbc.update("delete from storage.objects where id = ?", FEATURE_OBJECT);
        jdbc.update("delete from market_data.dataset_manifests where id = ?", FEATURE_MANIFEST);
        jdbc.update("delete from market_data.pipeline_runs where id = ?", PIPELINE);
        jdbc.update("delete from market_data.feature_definitions where id = ?", FEATURE);
        jdbc.update("delete from market_data.instruments where id = ?", INSTRUMENT);
        jdbc.update("delete from market_data.dataset_manifests where id = ?", DATASET);
        jdbc.update("delete from market_data.feeds where id in (?, ?)", FEED, FEATURE_FEED);
        jdbc.update("delete from market_data.providers where id = ?", PROVIDER);
        jdbc.update("delete from trading.fee_policy_versions where id = ?", FEE);
        jdbc.update("delete from trading.buying_power_buffer_policy_versions where id = ?", BUFFER);
        jdbc.update("delete from backtest.execution_policy_versions where version = ?", POLICY);
        jdbc.update("delete from strategy.element_catalog_versions where id = ?", CATALOG);
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE') on conflict (id) do nothing",
                ACCOUNT);
        jdbc.update(
                "insert into backtest.execution_policy_versions "
                        + "(version, policy_artifact_hash, policy_document, locked_at) "
                        + "values (?, ?, '{}'::jsonb, ?)",
                POLICY, "9".repeat(64), at.minusDays(1));
        jdbc.update(
                "insert into market_data.providers "
                        + "(id, code, display_name, rights_version, status, created_at) "
                        + "values (?, 'IDEA2STRATEGY_INTERNAL', 'Custom Test', 'v1', 'ACTIVE', ?)",
                PROVIDER, at);
        jdbc.update(
                "insert into market_data.feeds "
                        + "(id, provider_id, code, data_kind, resolution, timezone_name, feed_version, created_at) "
                        + "values (?, ?, 'CUSTOM_TEST', 'BAR', '1d', 'UTC', 'v1', ?)",
                FEED, PROVIDER, at);
        jdbc.update(
                "insert into market_data.feeds "
                        + "(id, provider_id, code, data_kind, resolution, timezone_name, feed_version, created_at) "
                        + "values (?, ?, 'FEATURE_RSI_14_1D_RSI_1_0_0', 'FEATURE_SERIES', '1d', 'UTC', "
                        + "'rsi-1.0.0+feature-series.parquet.v1', ?)",
                FEATURE_FEED, PROVIDER, at);
        jdbc.update(
                "insert into market_data.dataset_manifests "
                        + "(id, feed_id, data_layer, resolution, revision_number, status, period_start, period_end, "
                        + "schema_version, dataset_hash, created_at, available_at) "
                        + "values (?, ?, 'ADJUSTED', '1d', 1, 'AVAILABLE', '2024-01-01T00:00:00Z', "
                        + "'2024-12-31T23:59:59Z', 'v1', ?, ?, ?)",
                DATASET, FEED, "a".repeat(64), at, at);
        jdbc.update("insert into strategy.element_catalog_versions "
                        + "(id, language_version, schema_version, catalog_version, data_requirement_version, "
                        + "definition_hash, published_at) values (?, 'basic/v1', 'schema/v1', 'catalog/v1', "
                        + "'data/v1', ?, ?)", CATALOG, "b".repeat(64), at.minusDays(1));
        jdbc.update("insert into market_data.instruments "
                        + "(id, asset_type, primary_exchange_mic, currency_code) values (?, 'STOCK', 'XNAS', 'USD')",
                INSTRUMENT);
        jdbc.update("insert into market_data.feature_definitions "
                        + "(id, element_catalog_version_id, feature_code, calculator_version, resolution, "
                        + "normalized_parameters, output_value_type, required_history_points, definition_hash) "
                        + "values (?, ?, 'RSI_14', 'rsi:1.0.0', '1d', '{}'::jsonb, 'DECIMAL', 14, ?)",
                FEATURE, CATALOG, "c".repeat(64));
        jdbc.update("insert into market_data.pipeline_runs "
                        + "(id, pipeline_code, pipeline_version, idempotency_key, status, input_hash, output_hash, "
                        + "started_at, completed_at) values (?, 'FEATURE', 'v1', ?, 'SUCCEEDED', ?, ?, ?, ?)",
                PIPELINE, PIPELINE.toString(), "b".repeat(64), "f".repeat(64), at.minusDays(1), at.minusDays(1));
        jdbc.update("insert into market_data.dataset_manifests "
                        + "(id, feed_id, instrument_id, data_layer, resolution, revision_number, status, period_start, "
                        + "period_end, schema_version, dataset_hash, created_at, available_at) values "
                        + "(?, ?, ?, 'DERIVED', '1d', 1, 'AVAILABLE', '2023-12-01T00:00:00Z', "
                        + "'2025-01-01T00:00:00Z', 'feature-series.parquet.v1', ?, ?, ?)",
                FEATURE_MANIFEST, FEATURE_FEED, INSTRUMENT, "d".repeat(64), at.minusDays(1), at.minusDays(1));
        jdbc.update("insert into storage.objects "
                        + "(id, status, storage_provider, bucket_name, object_key, provider_version_id, content_hash, "
                        + "byte_size, file_format, compression_codec, media_type, schema_version, row_count, "
                        + "period_start, period_end, retention_policy_version, created_at, verified_at) values "
                        + "(?, 'AVAILABLE', 'S3', 'test', 'features/custom.parquet', 'v1', ?, 100, 'PARQUET', "
                        + "'SNAPPY', 'application/vnd.apache.parquet', 'feature-series.parquet.v1', 366, "
                        + "'2023-12-01T00:00:00Z', '2025-01-01T00:00:00Z', 'v1', ?, ?)",
                FEATURE_OBJECT, "e".repeat(64), at.minusDays(1), at.minusDays(1));
        jdbc.update("insert into market_data.dataset_objects "
                        + "(id, dataset_manifest_id, object_id, object_kind, partition_granularity, partition_start, "
                        + "partition_end, period_start, period_end, shard_key, part_number, row_count) values "
                        + "(?, ?, ?, 'FEATURE_SERIES', 'YEAR', '2024-01-01', '2025-01-01', "
                        + "'2023-12-01T00:00:00Z', '2025-01-01T00:00:00Z', 'all', 1, 366)",
                FEATURE_DATASET_OBJECT, FEATURE_MANIFEST, FEATURE_OBJECT);
        jdbc.update("insert into market_data.feature_materializations "
                        + "(id, feature_definition_id, instrument_id, pipeline_run_id, input_dataset_set_hash, "
                        + "period_start, period_end, source_watermark, output_dataset_manifest_id, result_hash, "
                        + "status, available_at, created_at) values (?, ?, ?, ?, ?, '2023-12-01T00:00:00Z', "
                        + "'2025-01-01T00:00:00Z', 'complete', ?, ?, 'SUCCEEDED', ?, ?)",
                MATERIALIZATION, FEATURE, INSTRUMENT, PIPELINE, "b".repeat(64), FEATURE_MANIFEST,
                "f".repeat(64), at.minusDays(1), at.minusDays(1));
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
                        + "?::jsonb, ?)",
                BOT, "sha256:" + "1".repeat(64), planDocument(), at);
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
        assertThat(jdbc.queryForObject("select count(*) from backtest.runs", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap(
                        "select p.input_bundle_fingerprint, p.input_contract_version, "
                                + "p.compiled_plan_checksum, p.strategy_snapshot_hash, p.execution_policy_version, "
                                + "b.bundle_hash from backtest.run_input_pins p "
                                + "join backtest.input_bundles b on b.id = p.input_bundle_id "
                                + "where p.run_id = ?",
                        created.runId()))
                .satisfies(pin -> {
                    assertThat(pin.get("input_bundle_fingerprint")).asString().matches("sha256:[0-9a-f]{64}");
                    assertThat(pin.get("input_contract_version")).isEqualTo("backtest-request.v1");
                    assertThat(pin.get("compiled_plan_checksum")).isEqualTo("sha256:" + "1".repeat(64));
                    assertThat(pin.get("strategy_snapshot_hash")).isEqualTo("sha256:" + "f".repeat(64));
                    assertThat(pin.get("execution_policy_version")).isEqualTo(POLICY);
                    assertThat(pin.get("bundle_hash")).isEqualTo(pin.get("input_bundle_fingerprint"));
                });
        assertThat(jdbc.queryForMap(
                        "select d.dataset_manifest_id, d.purpose_code, d.locked_dataset_hash "
                                + "from backtest.input_datasets d join backtest.run_input_pins p "
                                + "on p.input_bundle_id = d.input_bundle_id where p.run_id = ?",
                        created.runId()))
                .containsEntry("dataset_manifest_id", DATASET)
                .containsEntry("purpose_code", "MARKET_BARS")
                .containsEntry("locked_dataset_hash", "sha256:" + "a".repeat(64));
        assertThat(jdbc.queryForMap(
                        "select f.feature_materialization_id, f.locked_result_hash "
                                + "from backtest.input_feature_materializations f join backtest.run_input_pins p "
                                + "on p.input_bundle_id = f.input_bundle_id where p.run_id = ?", created.runId()))
                .containsEntry("feature_materialization_id", MATERIALIZATION)
                .containsEntry("locked_result_hash", "sha256:" + "f".repeat(64));
        assertThat(jdbc.queryForMap(
                        "select id, message_id, lane::text as lane, execution_policy_version, "
                                + "canonical_payload_hash, aggregate_sequence from backtest.runs"))
                .satisfies(row -> {
                    assertThat(row.get("id")).isEqualTo(created.runId());
                    assertThat(row.get("message_id")).isEqualTo(created.messageId());
                    assertThat(row.get("lane")).isEqualTo("CUSTOM");
                    assertThat(row.get("execution_policy_version")).isEqualTo(POLICY);
                    assertThat(row.get("canonical_payload_hash")).isEqualTo(
                            jdbc.queryForObject("select payload_hash from operations.outbox_messages", String.class));
                    assertThat(row.get("aggregate_sequence")).isEqualTo(1L);
                });
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
        assertThat(jdbc.queryForObject(
                        "select jsonb_array_length(payload_document -> 'featureMaterializations') "
                                + "from operations.outbox_messages where id = ?", Integer.class, created.messageId()))
                .isEqualTo(1);

        assertThatThrownBy(() -> adapter.enqueue(
                        ACCOUNT,
                        command(LocalDate.parse("2024-02-01"), LocalDate.parse("2024-11-30")),
                        NOW.plusSeconds(60)))
                .isInstanceOf(BacktestRequestIdempotencyConflictException.class);
        assertThat(jdbc.queryForObject("select count(*) from backtest.runs", Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsAnUnknownExecutionPolicyWithoutWritingRunOrOutbox() {
        assertThatThrownBy(() -> adapter.enqueue(
                        ACCOUNT,
                        new CustomBacktestCommand(BOT, DATASET, LocalDate.parse("2024-01-01"),
                                LocalDate.parse("2024-12-31"), "missing-policy", "custom-key-2"),
                        NOW))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Locked backtest execution policy was not found");
        assertThat(jdbc.queryForObject("select count(*) from backtest.runs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from operations.outbox_messages", Integer.class)).isZero();
    }

    @Test
    void rejectsAnIncompleteRequiredFeatureSetBeforeWritingRunPinsOrOutbox() {
        jdbc.update("update market_data.feature_materializations set status = 'FAILED', "
                        + "output_dataset_manifest_id = null, result_hash = null, available_at = null where id = ?",
                MATERIALIZATION);

        assertThatThrownBy(() -> adapter.enqueue(
                        ACCOUNT,
                        command(LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31")),
                        NOW))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("exactly one");
        assertThat(jdbc.queryForObject("select count(*) from backtest.runs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from backtest.input_bundles", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from operations.outbox_messages", Integer.class)).isZero();
    }

    private static CustomBacktestCommand command(LocalDate start, LocalDate end) {
        return new CustomBacktestCommand(BOT, DATASET, start, end, POLICY, "custom-key-1");
    }

    private static UUID id(int suffix) {
        return UUID.fromString("99000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    private static String planDocument() {
        return "{\"instrumentCatalogVersion\":\"us-supported-universe:2026-08-04\","
                + "\"requiredFeatures\":[{\"requirementId\":\"rsi-14-pt24h\",\"featureId\":\"" + FEATURE
                + "\",\"featureVersion\":\"1.0.0\",\"instruments\":[\"" + INSTRUMENT
                + "\"],\"resolution\":\"PT24H\",\"requiredObservations\":13}]}";
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({BacktestRequestOutboxStore.class, FeatureMaterializationPinResolver.class,
            CustomBacktestJooqAdapter.class})
    static class TestApplication {}
}

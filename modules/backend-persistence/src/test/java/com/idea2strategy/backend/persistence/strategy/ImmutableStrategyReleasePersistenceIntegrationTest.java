package com.idea2strategy.backend.persistence.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseRejectedException;
import com.idea2strategy.backend.application.strategy.OfficialBacktestRequest;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease;
import com.idea2strategy.backend.messaging.strategybot.v1.StrategyBotContractFixtures;
import com.idea2strategy.backend.persistence.competition.RoomStrategyBotProvisioningJooqAdapter;
import com.idea2strategy.backend.persistence.backtest.FeatureMaterializationPinResolver;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
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
    private static final UUID SECOND_DATASET_ID = UUID.fromString("d0000000-0000-4000-8000-000000000012");
    private static final UUID FEED_ID = UUID.fromString("e0000000-0000-4000-8000-000000000011");
    private static final UUID SECOND_FEED_ID = UUID.fromString("e0000000-0000-4000-8000-000000000012");
    private static final UUID FEATURE_FEED_ID = UUID.fromString("39e0e076-89e0-5159-b113-a8f6778b7c9e");
    private static final UUID PROVIDER_ID = UUID.fromString("b9146ed9-dbb0-5323-93e3-8518f3851236");
    private static final UUID FEATURE_PIPELINE_ID = UUID.fromString("e1000000-0000-4000-8000-000000000011");
    private static final UUID FEATURE_MANIFEST_ID = UUID.fromString("e2000000-0000-4000-8000-000000000011");
    private static final UUID FEATURE_OBJECT_ID = UUID.fromString("e3000000-0000-4000-8000-000000000011");
    private static final UUID FEATURE_DATASET_OBJECT_ID = UUID.fromString("e4000000-0000-4000-8000-000000000011");
    private static final UUID FEATURE_MATERIALIZATION_ID = UUID.fromString("e5000000-0000-4000-8000-000000000011");
    private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final String HASH_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    /** The published contract plan digest. Distinct from every other fixture hash on purpose:
     * it used to reuse HASH_C, the digest the caller passed separately, so a request naming the
     * wrong artifact still matched and root #439 went undetected here. */
    private static final String PLAN_CHECKSUM = "sha256:" + "e".repeat(64);

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
        // A real policy document, not '{}'. The producer guard reads periodStart, periodEnd,
        // marketDataSchemaVersion and timezone from it, and an empty document is exactly the shape it
        // must refuse — a policy that does not say what it is compatible with cannot be checked
        // against a manifest (root #471). The window covers the market manifest's 2025 period stated
        // as local midnight in the policy timezone, which is how the two are compared.
        jdbc.update(
                "insert into backtest.execution_policy_versions "
                        + "(version, policy_artifact_hash, policy_document, locked_at) "
                        + "values ('backtest-policy-v1', ?, ?::jsonb, ?)",
                HASH_A,
                "{\"version\":\"backtest-policy-v1\",\"periodStart\":\"2025-01-01T05:00:00Z\","
                        + "\"periodEnd\":\"2026-01-01T05:00:00Z\",\"marketDataSchemaVersion\":\"v1\","
                        + "\"timezone\":\"America/New_York\"}",
                at);
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
        jdbc.update("delete from market_data.feeds where provider_id = ? and code = 'FEATURE_RSI_14_1M_RSI_1_0_0'", PROVIDER_ID);
        jdbc.update(
                "insert into market_data.feeds "
                        + "(id, provider_id, code, data_kind, resolution, timezone_name, feed_version, created_at) "
                        + "values (?, ?, 'OFFICIAL', 'BAR', '1d', 'UTC', 'v1', ?)",
                FEED_ID, PROVIDER_ID, at);
        jdbc.update(
                "insert into market_data.feeds "
                        + "(id, provider_id, code, data_kind, resolution, timezone_name, feed_version, created_at) "
                        + "values (?, ?, 'OFFICIAL_4H', 'BAR', '4h', 'UTC', 'v1', ?)",
                SECOND_FEED_ID, PROVIDER_ID, at);
        jdbc.update(
                "insert into market_data.feeds "
                        + "(id, provider_id, code, data_kind, resolution, timezone_name, feed_version, created_at) "
                        + "values (?, ?, 'FEATURE_RSI_14_1M_RSI_1_0_0', 'FEATURE_SERIES', '1m', 'UTC', "
                        + "'rsi-1.0.0+feature-series.parquet.v1', ?)",
                FEATURE_FEED_ID, PROVIDER_ID, at);
        jdbc.update(
                "insert into market_data.dataset_manifests "
                        + "(id, feed_id, data_layer, resolution, revision_number, status, period_start, period_end, "
                        + "schema_version, dataset_hash, created_at, available_at) "
                        + "values (?, ?, 'ADJUSTED', '1d', 1, 'AVAILABLE', '2025-01-01T00:00:00Z', "
                        + "'2025-12-31T00:00:00Z', 'v1', ?, ?, ?)",
                DATASET_ID, FEED_ID, HASH_D, at, at);
        jdbc.update(
                "insert into market_data.dataset_manifests "
                        + "(id, feed_id, data_layer, resolution, revision_number, status, period_start, period_end, "
                        + "schema_version, dataset_hash, created_at, available_at) "
                        + "values (?, ?, 'ADJUSTED', '4h', 1, 'AVAILABLE', '2025-01-01T00:00:00Z', "
                        + "'2025-12-31T00:00:00Z', 'v1', ?, ?, ?)",
                SECOND_DATASET_ID, SECOND_FEED_ID, HASH_C, at, at);
        jdbc.update(
                "insert into market_data.instruments "
                        + "(id, asset_type, primary_exchange_mic, currency_code) values (?, 'STOCK', 'XNAS', 'USD')",
                INSTRUMENT_ID);
        jdbc.update(
                "insert into market_data.feature_definitions "
                        + "(id, element_catalog_version_id, feature_code, calculator_version, resolution, "
                        + "normalized_parameters, output_value_type, required_history_points, definition_hash) "
                        + "values (?, ?, 'RSI_14', 'rsi:1.0.0', '1m', '{}'::jsonb, 'NUMBER', 14, ?)",
                FEATURE_ID, CATALOG_ID, "sha256:" + HASH_B);
        jdbc.update("insert into market_data.pipeline_runs "
                        + "(id, pipeline_code, pipeline_version, idempotency_key, status, input_hash, output_hash, "
                        + "started_at, completed_at) values (?, 'MATERIALIZE_FEATURE_OUTPUT', "
                        + "'feature-series.parquet.v1', ?, 'SUCCEEDED', ?, ?, ?, ?)",
                FEATURE_PIPELINE_ID, FEATURE_PIPELINE_ID.toString(), HASH_A, HASH_B, at, at);
        jdbc.update("insert into market_data.dataset_manifests "
                        + "(id, feed_id, instrument_id, data_layer, resolution, revision_number, status, period_start, "
                        + "period_end, schema_version, dataset_hash, created_at, available_at) values "
                        + "(?, ?, ?, 'DERIVED', '1m', 1, 'AVAILABLE', '2024-12-31T00:00:00Z', "
                        + "'2026-01-01T00:00:00Z', 'feature-series.parquet.v1', ?, ?, ?)",
                FEATURE_MANIFEST_ID, FEATURE_FEED_ID, INSTRUMENT_ID, HASH_A, at, at);
        jdbc.update("insert into storage.objects "
                        + "(id, status, storage_provider, bucket_name, object_key, provider_version_id, content_hash, "
                        + "byte_size, file_format, compression_codec, media_type, schema_version, row_count, "
                        + "period_start, period_end, retention_policy_version, created_at, verified_at) values "
                        + "(?, 'AVAILABLE', 'S3', 'test', 'features/rsi.parquet', 'v1', ?, 100, 'PARQUET', "
                        + "'SNAPPY', 'application/vnd.apache.parquet', 'feature-series.parquet.v1', 100, "
                        + "'2024-12-31T00:00:00Z', '2026-01-01T00:00:00Z', 'v1', ?, ?)",
                FEATURE_OBJECT_ID, HASH_A, at, at);
        jdbc.update("insert into market_data.dataset_objects "
                        + "(id, dataset_manifest_id, object_id, object_kind, partition_granularity, partition_start, "
                        + "partition_end, period_start, period_end, shard_key, part_number, row_count) values "
                        + "(?, ?, ?, 'FEATURE_SERIES', 'YEAR', '2025-01-01', '2026-01-01', "
                        + "'2024-12-31T00:00:00Z', '2026-01-01T00:00:00Z', 'all', 1, 100)",
                FEATURE_DATASET_OBJECT_ID, FEATURE_MANIFEST_ID, FEATURE_OBJECT_ID);
        jdbc.update("insert into market_data.feature_materializations "
                        + "(id, feature_definition_id, instrument_id, pipeline_run_id, input_dataset_set_hash, "
                        + "period_start, period_end, source_watermark, output_dataset_manifest_id, result_hash, "
                        + "status, available_at, created_at) values (?, ?, ?, ?, ?, '2024-12-31T00:00:00Z', "
                        + "'2026-01-01T00:00:00Z', 'complete', ?, ?, 'SUCCEEDED', ?, ?)",
                FEATURE_MATERIALIZATION_ID, FEATURE_ID, INSTRUMENT_ID, FEATURE_PIPELINE_ID, HASH_A,
                FEATURE_MANIFEST_ID, HASH_B, at, at);
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
    void atomicallyCreatesOneImmutableAggregateAndMakesTheReleaseIdIdempotent() throws Exception {
        ImmutableStrategyRelease release = release(BOT_ID, HASH_D);
        OfficialBacktestRequest request = OfficialBacktestRequest.forRelease(
                release, List.of(DATASET_ID, SECOND_DATASET_ID), "backtest-policy-v1");

        jdbc.update("update strategy.element_catalog_versions set retired_at = ? where id = ?",
                NOW.atOffset(ZoneOffset.UTC), CATALOG_ID);
        assertThatThrownBy(() -> adapter.saveOnce(release, request, RUN_ID, 7, HASH_A))
                .isInstanceOf(ImmutableStrategyReleaseRejectedException.class)
                .hasMessage("Compiled flow plan or catalog does not match the validated strategy");
        assertThat(count("bot.bots")).isZero();
        assertThat(count("backtest.runs")).isZero();
        assertThat(count("operations.outbox_messages")).isZero();
        jdbc.update("update strategy.element_catalog_versions set retired_at = null where id = ?", CATALOG_ID);

        jdbc.update("update market_data.pipeline_runs set output_hash = ? where id = ?", HASH_C, FEATURE_PIPELINE_ID);
        assertThatThrownBy(() -> adapter.saveOnce(release, request, RUN_ID, 7, HASH_A))
                .isInstanceOf(ImmutableStrategyReleaseRejectedException.class)
                .hasMessageContaining("pipeline result hash");
        assertThat(count("bot.bots")).isZero();
        assertThat(count("backtest.runs")).isZero();
        assertThat(count("backtest.input_bundles")).isZero();
        assertThat(count("operations.outbox_messages")).isZero();
        jdbc.update("update market_data.pipeline_runs set output_hash = ? where id = ?", HASH_B, FEATURE_PIPELINE_ID);

        assertIncompatiblePolicyAndManifestAreRefused(release, request);

        assertThat(adapter.saveOnce(release, request, RUN_ID, 7, HASH_A)).isEqualTo(release);
        assertThat(adapter.saveOnce(release, request, RUN_ID, 7, HASH_A)).isEqualTo(release);

        assertThat(count("bot.bots")).isEqualTo(1);
        assertThat(count("bot.launch_snapshots")).isEqualTo(1);
        assertThat(count("bot.launch_configurations")).isEqualTo(1);
        assertThat(count("bot.bot_partitions")).isEqualTo(1);
        assertThat(count("bot.flows")).isEqualTo(1);
        assertThat(count("bot.flow_instruments")).isEqualTo(1);
        assertThat(count("bot.flow_feature_requirements")).isEqualTo(1);
        // Root #190: the plan the evaluation runtime loads the bot from lands in the same transaction as
        // the snapshot whose hash it pins, and a redelivered release adds no second one.
        assertThat(count("bot.launch_contract_plans")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select plan_checksum from bot.launch_contract_plans where bot_id = ?",
                        String.class, BOT_ID))
                .isEqualTo(release.contractPlan().planChecksum());
        assertThat(count("backtest.runs")).isEqualTo(1);
        assertThat(count("backtest.run_input_pins")).isEqualTo(1);
        assertThat(count("backtest.input_bundles")).isEqualTo(1);
        assertThat(count("backtest.input_datasets")).isEqualTo(2);
        assertThat(count("backtest.input_feature_materializations")).isEqualTo(1);
        assertThat(count("operations.outbox_messages")).isEqualTo(1);
        // The transport aggregate for a BASIC official backtest is the bot, not the run. The BASIC
        // consumer rejects the envelope as TRANSPORT_ENVELOPE_MISMATCH when aggregate_id is not the
        // payload botId, and SqsOutboxMessagePublisher publishes this column verbatim — so a row
        // written under runId is dead-lettered before any attempt is recorded (backend #243).
        assertThat(jdbc.queryForObject(
                        "select count(*) from operations.outbox_messages where aggregate_id = ?",
                        Integer.class,
                        request.runId()))
                .as("the run id must not be used as the strategy-bot transport aggregate")
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select payload_document ->> 'datasetManifestId' from operations.outbox_messages "
                                + "where aggregate_id = ?", String.class, BOT_ID))
                .isEqualTo(DATASET_ID.toString());
        assertThat(jdbc.queryForObject(
                        "select jsonb_array_length(payload_document -> 'datasets') "
                                + "from operations.outbox_messages where aggregate_id = ?",
                        Integer.class, BOT_ID))
                .isEqualTo(2);
        var transported = OBJECT_MAPPER.readValue(
                jdbc.queryForObject(
                        "select payload_document::text from operations.outbox_messages where aggregate_id = ?",
                        String.class,
                        BOT_ID),
                StrategyBotContractFixtures.OfficialBacktestRequest.class);
        // Tie the row's identity to the payload rather than to a constant. This is the assertion the
        // defect would have failed: the envelope and the body must name the same aggregate.
        assertThat(jdbc.queryForObject(
                        "select aggregate_id::text from operations.outbox_messages where owner_domain = 'strategy-bot'",
                        String.class))
                .as("transport aggregate identity must agree with the payload botId")
                .isEqualTo(transported.botId());
        assertThat(transported.metadata().contractVersion()).isEqualTo("strategy-bot.v1");
        assertThat(transported.metadata().messageType()).isEqualTo("OFFICIAL_BACKTEST_REQUESTED");
        assertThat(transported.metadata().messageId()).isEqualTo(request.metadata().messageId().toString());
        assertThat(transported.metadata().idempotencyKey()).isEqualTo(request.metadata().idempotencyKey());
        assertThat(transported.botId()).isEqualTo(BOT_ID.toString());
        assertThat(transported.runId()).isEqualTo(request.runId().toString());
        assertThat(transported.executionPolicyVersion()).isEqualTo("backtest-policy-v1");
        assertThat(transported.expectedSnapshotHash()).isEqualTo("sha256:" + HASH_D);
        assertThat(transported.compiledPlanChecksum()).isEqualTo(PLAN_CHECKSUM);
        assertThat(transported.datasetManifestId()).isEqualTo(DATASET_ID.toString());
        assertThat(transported.expectedDatasetHash()).isEqualTo("sha256:" + HASH_D);
        assertThat(transported.featureMaterializations()).containsExactly(
                new StrategyBotContractFixtures.PinnedFeatureMaterialization(
                        FEATURE_MATERIALIZATION_ID.toString(), "sha256:" + HASH_B));
        assertThat(transported.periodStart()).isEqualTo("2025-01-01");
        assertThat(transported.periodEnd()).isEqualTo("2025-12-31");
        assertThat(transported.requestHash()).matches("sha256:[0-9a-f]{64}");
        assertThat(jdbc.queryForMap(
                        "select p.input_bundle_fingerprint, p.input_contract_version, "
                                + "p.compiled_plan_checksum, p.strategy_snapshot_hash, p.execution_policy_version, "
                                + "b.bundle_hash from backtest.run_input_pins p "
                                + "join backtest.input_bundles b on b.id = p.input_bundle_id where p.run_id = ?",
                        request.runId()))
                .containsEntry("input_bundle_fingerprint", transported.requestHash())
                .containsEntry("input_contract_version", "strategy-bot.v1")
                .containsEntry("compiled_plan_checksum", PLAN_CHECKSUM)
                .containsEntry("strategy_snapshot_hash", "sha256:" + HASH_D)
                .containsEntry("execution_policy_version", "backtest-policy-v1")
                .containsEntry("bundle_hash", transported.requestHash());
        assertThat(jdbc.queryForObject(
                        "select event_schema_version from operations.outbox_messages where aggregate_id = ?",
                        String.class,
                        BOT_ID))
                .isEqualTo(transported.metadata().contractVersion());
        assertThat(jdbc.queryForObject(
                        "select event_type from operations.outbox_messages where aggregate_id = ?",
                        String.class,
                        BOT_ID))
                .isEqualTo(transported.metadata().messageType());
        assertThat(jdbc.queryForObject(
                        "select id::text from operations.outbox_messages where aggregate_id = ?",
                        String.class,
                        BOT_ID))
                .isEqualTo(transported.metadata().messageId());
        assertThat(jdbc.queryForObject(
                        "select idempotency_key from operations.outbox_messages where aggregate_id = ?",
                        String.class,
                        BOT_ID))
                .isEqualTo(transported.metadata().idempotencyKey());
        assertThat("sha256:" + jdbc.queryForObject(
                        "select snapshot_hash from bot.launch_snapshots where bot_id = ?",
                        String.class,
                        BOT_ID))
                .isEqualTo(transported.expectedSnapshotHash());
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

    /**
     * An official request whose policy and manifest cannot describe the same replay must leave nothing
     * behind.
     *
     * <p>The producer published any available manifest against any locked policy, so a ten-year policy
     * met a one-month manifest and a {@code market-bars-v2} policy met {@code market-bars/1} objects.
     * The consumer only found out after the run, its input pins and its Outbox row were already durable,
     * which is what made the frozen input set unreplayable (root #471). Each case below asserts the four
     * durable tables stay empty, because a guard that rejects after writing is not a guard.
     *
     * <p>Called from the aggregate test rather than standing alone: this class seeds its fixtures once
     * per test and does not clean between them, and every case here has to run before anything durable
     * exists.
     */
    private void assertIncompatiblePolicyAndManifestAreRefused(
            ImmutableStrategyRelease release, OfficialBacktestRequest request) {
        // 1. The manifest states a schema the policy does not.
        setPolicyDocument("2025-01-01T05:00:00Z", "2026-01-01T05:00:00Z", "market-bars-v2");
        assertThatThrownBy(() -> adapter.saveOnce(release, request, RUN_ID, 7, HASH_A))
                .isInstanceOf(ImmutableStrategyReleaseRejectedException.class)
                .hasMessageContaining("does not match the execution policy schema");
        assertNothingDurable();

        // 2. The manifest period reaches outside the policy window.
        setPolicyDocument("2025-06-01T04:00:00Z", "2025-07-01T04:00:00Z", "v1");
        assertThatThrownBy(() -> adapter.saveOnce(release, request, RUN_ID, 7, HASH_A))
                .isInstanceOf(ImmutableStrategyReleaseRejectedException.class)
                .hasMessageContaining("is not inside the execution policy period");
        assertNothingDurable();

        // 3. A RAW manifest would measure the strategy against unadjusted splits and dividends.
        setPolicyDocument("2025-01-01T05:00:00Z", "2026-01-01T05:00:00Z", "v1");
        jdbc.update("update market_data.dataset_manifests set data_layer = 'RAW' where id = ?", DATASET_ID);
        assertThatThrownBy(() -> adapter.saveOnce(release, request, RUN_ID, 7, HASH_A))
                .isInstanceOf(ImmutableStrategyReleaseRejectedException.class)
                .hasMessageContaining("must be ADJUSTED");
        assertNothingDurable();
        jdbc.update("update market_data.dataset_manifests set data_layer = 'ADJUSTED' where id = ?", DATASET_ID);

        // 4. A policy retired at the release instant is no longer selectable, locked_at notwithstanding.
        jdbc.update("update backtest.execution_policy_versions set retired_at = ? where version = ?",
                NOW.atOffset(ZoneOffset.UTC), "backtest-policy-v1");
        assertThatThrownBy(() -> adapter.saveOnce(release, request, RUN_ID, 7, HASH_A))
                .isInstanceOf(ImmutableStrategyReleaseRejectedException.class)
                .hasMessageContaining("must be locked and not retired");
        assertNothingDurable();
        jdbc.update("update backtest.execution_policy_versions set retired_at = null where version = ?",
                "backtest-policy-v1");

        // A policy that states nothing cannot be checked, so it cannot be used.
        jdbc.update("update backtest.execution_policy_versions set policy_document = '{}'::jsonb "
                + "where version = ?", "backtest-policy-v1");
        assertThatThrownBy(() -> adapter.saveOnce(release, request, RUN_ID, 7, HASH_A))
                .isInstanceOf(ImmutableStrategyReleaseRejectedException.class)
                .hasMessageContaining("does not state its period and market data schema");
        assertNothingDurable();

        // Restore the compatible pair so the caller can go on to prove the success path.
        setPolicyDocument("2025-01-01T05:00:00Z", "2026-01-01T05:00:00Z", "v1");
    }

    private void setPolicyDocument(String periodStart, String periodEnd, String schemaVersion) {
        jdbc.update(
                "update backtest.execution_policy_versions set policy_document = ?::jsonb where version = ?",
                "{\"version\":\"backtest-policy-v1\",\"periodStart\":\"" + periodStart + "\","
                        + "\"periodEnd\":\"" + periodEnd + "\",\"marketDataSchemaVersion\":\""
                        + schemaVersion + "\",\"timezone\":\"America/New_York\"}",
                "backtest-policy-v1");
    }

    private void assertNothingDurable() {
        assertThat(count("bot.bots")).isZero();
        assertThat(count("backtest.runs")).isZero();
        assertThat(count("backtest.input_bundles")).isZero();
        assertThat(count("operations.outbox_messages")).isZero();
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
                HASH_A, HASH_B, snapshotHash, configuration, partition, contractPlan(), NOW);
    }

    private static ImmutableStrategyRelease.ContractPlan contractPlan() {
        return new ImmutableStrategyRelease.ContractPlan(
                "strategy-bot.v1", "basic-compiled-plan.v1", PLAN_CHECKSUM,
                "{\"contractVersion\":\"strategy-bot.v1\",\"requiredFeatures\":[{"
                        + "\"requirementId\":\"rsi-14-pt1m\",\"featureId\":\"" + FEATURE_ID + "\","
                        + "\"featureVersion\":\"1.0.0\",\"instruments\":[\"" + INSTRUMENT_ID + "\"],"
                        + "\"resolution\":\"PT1M\",\"requiredObservations\":13}]}");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({FeatureMaterializationPinResolver.class, ImmutableStrategyReleaseJooqCommandAdapter.class,
            RoomStrategyBotProvisioningJooqAdapter.class})
    static class TestApplication {}
}

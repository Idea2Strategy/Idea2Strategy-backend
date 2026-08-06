package com.idea2strategy.backend.persistence.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
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
@SpringBootTest(classes = FeatureMaterializationPinResolverIntegrationTest.TestApplication.class)
class FeatureMaterializationPinResolverIntegrationTest {
    private static final UUID CATALOG = id(1);
    private static final String HASH = "a".repeat(64);
    private static final String DEFINITION_HASH = "sha256:" + HASH;
    private static final UUID PROVIDER = FeatureMaterializationPinResolver.deterministicUuid(
            "provider", "IDEA2STRATEGY_INTERNAL");
    private static final UUID FEED = FeatureMaterializationPinResolver.deterministicUuid(
            "feature-output-feed", DEFINITION_HASH, "rsi:1.0.0", "1d",
            FeatureMaterializationPinResolver.OUTPUT_SCHEMA);
    private static final UUID INSTRUMENT = id(4);
    private static final UUID FEATURE = id(5);
    private static final UUID PIPELINE = id(6);
    private static final UUID MANIFEST = id(7);
    private static final UUID OBJECT = id(8);
    private static final UUID DATASET_OBJECT = id(9);
    private static final UUID MATERIALIZATION = id(10);
    private static final OffsetDateTime AS_OF = OffsetDateTime.parse("2026-08-04T12:00:00Z");

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

    @Autowired DSLContext dsl;
    @Autowired JdbcTemplate jdbc;

    private FeatureMaterializationPinResolver resolver;

    @BeforeEach
    void seed() {
        resolver = new FeatureMaterializationPinResolver(dsl);
        jdbc.update("delete from market_data.feature_materializations where feature_definition_id = ?", FEATURE);
        jdbc.update("delete from market_data.dataset_objects where dataset_manifest_id in "
                + "(select id from market_data.dataset_manifests where feed_id = ?)", FEED);
        jdbc.update("delete from storage.objects where bucket_name = 'test'");
        jdbc.update("delete from market_data.dataset_manifests where feed_id = ?", FEED);
        jdbc.update("delete from market_data.pipeline_runs where pipeline_code = 'FEATURE'");
        jdbc.update("delete from market_data.feature_definitions where id = ?", FEATURE);
        jdbc.update("delete from market_data.instruments where id = ?", INSTRUMENT);
        jdbc.update("delete from market_data.feeds where id = ?", FEED);
        jdbc.update("delete from market_data.providers where id = ?", PROVIDER);
        jdbc.update("delete from strategy.element_catalog_versions where id = ?", CATALOG);
        var created = AS_OF.minusDays(1);
        jdbc.update("insert into strategy.element_catalog_versions "
                        + "(id, language_version, schema_version, catalog_version, data_requirement_version, "
                        + "definition_hash, published_at) values (?, 'basic/v1', 'schema/v1', 'catalog/v1', "
                        + "'data/v1', ?, ?)", CATALOG, HASH, created);
        jdbc.update("insert into market_data.providers "
                        + "(id, code, display_name, rights_version, status, created_at) "
                        + "values (?, 'IDEA2STRATEGY_INTERNAL', 'Feature Test', 'internal-derived-v1', 'ACTIVE', ?)",
                PROVIDER, created);
        jdbc.update("insert into market_data.feeds "
                        + "(id, provider_id, code, data_kind, resolution, timezone_name, feed_version, created_at) "
                        + "values (?, ?, 'FEATURE_RSI_14_1D_RSI_1_0_0', 'FEATURE_SERIES', '1d', 'UTC', "
                        + "'rsi-1.0.0+feature-series.parquet.v1', ?)",
                FEED, PROVIDER, created);
        jdbc.update("insert into market_data.instruments "
                        + "(id, asset_type, primary_exchange_mic, currency_code) values (?, 'STOCK', 'XNAS', 'USD')",
                INSTRUMENT);
        jdbc.update("insert into market_data.feature_definitions "
                        + "(id, element_catalog_version_id, feature_code, calculator_version, resolution, "
                        + "normalized_parameters, output_value_type, required_history_points, definition_hash) "
                        + "values (?, ?, 'RSI_14', 'rsi:1.0.0', '1d', '{}'::jsonb, 'DECIMAL', 14, ?)",
                FEATURE, CATALOG, DEFINITION_HASH);
        seedMaterialization(MATERIALIZATION, PIPELINE, MANIFEST, OBJECT, DATASET_OBJECT, "b".repeat(64));
    }

    @Test
    void resolvesExactlyOneCompleteVersionedOutputForEveryPlanTuple() {
        var pins = resolver.resolve(plan(), LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"), AS_OF);

        assertThat(pins).containsExactly(new BacktestRunInputPinWriter.FeaturePin(
                MATERIALIZATION, "sha256:" + HASH));
    }

    @Test
    void rejectsMissingDuplicateAndManifestMismatchBeforeAPinCanBePublished() {
        jdbc.update("update market_data.feature_materializations set status = 'FAILED', "
                + "output_dataset_manifest_id = null, result_hash = null, available_at = null where id = ?",
                MATERIALIZATION);
        assertThatThrownBy(() -> resolver.resolve(
                        plan(), LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"), AS_OF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one");

        jdbc.update("update market_data.feature_materializations set status = 'SUCCEEDED', "
                        + "output_dataset_manifest_id = ?, result_hash = ?, available_at = ? where id = ?",
                MANIFEST, HASH, AS_OF.minusDays(1), MATERIALIZATION);
        seedMaterialization(id(20), id(21), id(22), id(23), id(24), "c".repeat(64));
        assertThatThrownBy(() -> resolver.resolve(
                        plan(), LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"), AS_OF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one");

        jdbc.update("delete from market_data.feature_materializations where id = ?", id(20));
        jdbc.update("update market_data.dataset_manifests set schema_version = 'unknown.v1' where id = ?", MANIFEST);
        assertThatThrownBy(() -> resolver.resolve(
                        plan(), LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"), AS_OF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("feature-series.parquet.v1");
    }

    @Test
    void rejectsStaleUnavailableAndHashInconsistentPublicationMetadata() {
        jdbc.update("update market_data.pipeline_runs set output_hash = ? where id = ?", "c".repeat(64), PIPELINE);
        assertThatThrownBy(() -> resolver.resolve(
                        plan(), LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"), AS_OF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pipeline result hash");
        jdbc.update("update market_data.pipeline_runs set output_hash = ? where id = ?", HASH, PIPELINE);

        jdbc.update("update storage.objects set verified_at = null where id = ?", OBJECT);
        assertThatThrownBy(() -> resolver.resolve(
                        plan(), LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"), AS_OF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("complete versioned");
        jdbc.update("update storage.objects set verified_at = ? where id = ?", AS_OF.minusDays(1), OBJECT);

        jdbc.update("update market_data.feeds set retired_at = ? where id = ?", AS_OF.minusHours(1), FEED);
        assertThatThrownBy(() -> resolver.resolve(
                        plan(), LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"), AS_OF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("feature output feed");
    }

    @Test
    void rejectsAFeedThatDoesNotMatchTheDefinitionsDeterministicIdentity() {
        jdbc.update("update market_data.feeds set code = 'FEATURE_OTHER_1D_RSI_1_0_0' where id = ?", FEED);
        assertRejected("feature output feed identity");
        jdbc.update("update market_data.feeds set code = 'FEATURE_RSI_14_1D_RSI_1_0_0' where id = ?", FEED);

        jdbc.update("update market_data.feeds set feed_version = 'rsi-2.0.0+feature-series.parquet.v1' where id = ?", FEED);
        assertRejected("feature output feed identity");
        jdbc.update("update market_data.feeds set feed_version = 'rsi-1.0.0+feature-series.parquet.v1' where id = ?", FEED);

        jdbc.update("update market_data.providers set rights_version = 'wrong-rights' where id = ?", PROVIDER);
        assertRejected("feature output feed identity");
    }

    @Test
    void rejectsAPipelineRunThatDoesNotOwnTheMaterializationInputAndSchema() {
        jdbc.update("update market_data.pipeline_runs set pipeline_code = 'OTHER' where id = ?", PIPELINE);
        assertRejected("pipeline identity/input");
        jdbc.update("update market_data.pipeline_runs set pipeline_code = 'MATERIALIZE_FEATURE_OUTPUT' where id = ?", PIPELINE);

        jdbc.update("update market_data.pipeline_runs set pipeline_version = 'unknown.v1' where id = ?", PIPELINE);
        assertRejected("pipeline identity/input");
        jdbc.update("update market_data.pipeline_runs set pipeline_version = ? where id = ?", FeatureMaterializationPinResolver.OUTPUT_SCHEMA, PIPELINE);

        jdbc.update("update market_data.pipeline_runs set input_hash = ? where id = ?", "c".repeat(64), PIPELINE);
        assertRejected("pipeline identity/input");
    }

    @Test
    void rejectsObjectReceiptsWhosePeriodsOrRowsDoNotAuthoritativelyCoverTheManifest() {
        jdbc.update("update market_data.dataset_objects set row_count = row_count - 1 where id = ?", DATASET_OBJECT);
        assertRejected("complete versioned");
        jdbc.update("update market_data.dataset_objects set row_count = row_count + 1 where id = ?", DATASET_OBJECT);

        jdbc.update("update market_data.dataset_objects set period_start = period_start + interval '1 day' where id = ?", DATASET_OBJECT);
        assertRejected("complete versioned");
        jdbc.update("update market_data.dataset_objects set period_start = period_start - interval '1 day' where id = ?", DATASET_OBJECT);

        jdbc.update("update storage.objects set period_end = '2024-06-01T00:00:00Z' where id = ?", OBJECT);
        assertRejected("complete versioned");
    }

    private void assertRejected(String message) {
        assertThatThrownBy(() -> resolver.resolve(
                        plan(), LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"), AS_OF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(message);
    }

    private void seedMaterialization(
            UUID materialization, UUID pipeline, UUID manifest, UUID object, UUID datasetObject, String inputHash) {
        var created = AS_OF.minusDays(1);
        jdbc.update("insert into market_data.pipeline_runs "
                        + "(id, pipeline_code, pipeline_version, idempotency_key, status, input_hash, output_hash, "
                        + "started_at, completed_at) values (?, 'MATERIALIZE_FEATURE_OUTPUT', ?, ?, "
                        + "'SUCCEEDED', ?, ?, ?, ?)",
                pipeline, FeatureMaterializationPinResolver.OUTPUT_SCHEMA, pipeline.toString(), inputHash, HASH,
                created, created);
        jdbc.update("insert into market_data.dataset_manifests "
                        + "(id, feed_id, instrument_id, data_layer, resolution, revision_number, status, period_start, "
                        + "period_end, schema_version, dataset_hash, created_at, available_at) values "
                        + "(?, ?, ?, 'DERIVED', '1d', ?, 'AVAILABLE', '2023-12-01T00:00:00Z', "
                        + "'2025-01-01T00:00:00Z', 'feature-series.parquet.v1', ?, ?, ?)",
                manifest, FEED, INSTRUMENT, materialization.equals(MATERIALIZATION) ? 1 : 2,
                materialization.equals(MATERIALIZATION) ? HASH : "d".repeat(64), created, created);
        jdbc.update("insert into storage.objects "
                        + "(id, status, storage_provider, bucket_name, object_key, provider_version_id, content_hash, "
                        + "byte_size, file_format, compression_codec, media_type, schema_version, row_count, "
                        + "period_start, period_end, retention_policy_version, created_at, verified_at) values "
                        + "(?, 'AVAILABLE', 'S3', 'test', ?, ?, ?, 100, 'PARQUET', 'SNAPPY', "
                        + "'application/vnd.apache.parquet', 'feature-series.parquet.v1', 366, "
                        + "'2023-12-01T00:00:00Z', '2025-01-01T00:00:00Z', 'v1', ?, ?)",
                object, object.toString(), "version-" + object, HASH, created, created);
        jdbc.update("insert into market_data.dataset_objects "
                        + "(id, dataset_manifest_id, object_id, object_kind, partition_granularity, partition_start, "
                        + "partition_end, period_start, period_end, shard_key, part_number, row_count) values "
                        + "(?, ?, ?, 'FEATURE_SERIES', 'YEAR', '2024-01-01', '2025-01-01', "
                        + "'2023-12-01T00:00:00Z', '2025-01-01T00:00:00Z', 'all', 1, 366)",
                datasetObject, manifest, object);
        jdbc.update("insert into market_data.feature_materializations "
                        + "(id, feature_definition_id, instrument_id, pipeline_run_id, input_dataset_set_hash, "
                        + "period_start, period_end, source_watermark, output_dataset_manifest_id, result_hash, "
                        + "status, available_at, created_at) values (?, ?, ?, ?, ?, '2023-12-01T00:00:00Z', "
                        + "'2025-01-01T00:00:00Z', 'complete', ?, ?, 'SUCCEEDED', ?, ?)",
                materialization, FEATURE, INSTRUMENT, pipeline, inputHash, manifest, HASH, created, created);
    }

    private static String plan() {
        return "{\"requiredFeatures\":[{\"requirementId\":\"rsi-14-pt24h\",\"featureId\":\"" + FEATURE
                + "\",\"featureVersion\":\"1.0.0\",\"instruments\":[\"" + INSTRUMENT
                + "\"],\"resolution\":\"PT24H\",\"requiredObservations\":13}]}";
    }

    private static UUID id(int suffix) {
        return UUID.fromString("98000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(FeatureMaterializationPinResolver.class)
    static class TestApplication {}
}

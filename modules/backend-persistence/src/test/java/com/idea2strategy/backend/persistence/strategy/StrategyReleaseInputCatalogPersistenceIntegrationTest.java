package com.idea2strategy.backend.persistence.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
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
@SpringBootTest(classes = StrategyReleaseInputCatalogPersistenceIntegrationTest.TestApplication.class)
class StrategyReleaseInputCatalogPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-25T06:00:00Z");
    private static final UUID PROVIDER = UUID.fromString("71000000-0000-4000-8000-000000000001");
    private static final UUID MARKET_FEED = UUID.fromString("71000000-0000-4000-8000-000000000002");
    private static final UUID FEATURE_FEED = UUID.fromString("57794d8c-2254-53e4-966e-44f97edd9e6a");
    private static final UUID MARKET_DATASET = UUID.fromString("71000000-0000-4000-8000-000000000003");
    private static final UUID FEATURE_DATASET = UUID.fromString("71000000-0000-4000-8000-000000000004");
    private static final UUID FEE_POLICY = UUID.fromString("71000000-0000-4000-8000-000000000005");
    private static final UUID BUFFER_POLICY = UUID.fromString("71000000-0000-4000-8000-000000000006");

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
    private StrategyReleaseInputCatalogJooqQueryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedDatasets() {
        jdbc.update("insert into market_data.providers (id,code,display_name,rights_version,status,created_at) "
                + "values (?, 'LOCAL_MARKET', 'Local market', 'v1', 'ACTIVE', ?)",
                PROVIDER, NOW.atOffset(ZoneOffset.UTC));
        jdbc.update("insert into market_data.feeds "
                + "(id,provider_id,code,data_kind,resolution,timezone_name,feed_version,created_at) "
                + "values (?, ?, 'LOCAL_MARKET_30M', 'BARS', '30m', 'UTC', 'v1', ?)",
                MARKET_FEED, PROVIDER, NOW.atOffset(ZoneOffset.UTC));
        insertDataset(MARKET_DATASET, MARKET_FEED, "ADJUSTED", "a".repeat(64));
        insertDataset(FEATURE_DATASET, FEATURE_FEED, "DERIVED", "b".repeat(64));
        seedPolicies();
    }

    @Test
    void exposesOnlyOfficialAdjustedMarketDatasets() {
        var catalog = adapter.findSelectableAt(NOW.plusSeconds(60));

        assertThat(catalog.datasets())
                .extracting(dataset -> dataset.id())
                .containsExactly(MARKET_DATASET);
        assertThat(catalog.executionPolicies())
                .extracting(policy -> policy.version())
                .containsExactly("compatible-policy");
    }

    private void seedPolicies() {
        jdbc.update("insert into trading.fee_policy_versions "
                        + "(id,policy_code,version,fee_rate_bps,calculation_rules_version,rules_hash,effective_from,published_at) "
                        + "values (?, 'local-fee', 'v1', 20, 'accounting-v1', ?, ?, ?)",
                FEE_POLICY, "c".repeat(64), NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC),
                NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC));
        jdbc.update("insert into trading.buying_power_buffer_policy_versions "
                        + "(id,policy_code,version,buffer_bps,rounding_rules_version,rules_hash,effective_from,published_at) "
                        + "values (?, 'local-buffer', 'v1', 1, 'precision-v1', ?, ?, ?)",
                BUFFER_POLICY, "d".repeat(64), NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC),
                NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC));
        insertPolicy("compatible-policy", "market-bars/1", "e".repeat(64));
        insertPolicy("incompatible-policy", "market-bars-v2", "f".repeat(64));
    }

    private void insertPolicy(String version, String schema, String hash) {
        String document = """
                {"marketRulesVersion":"market-v1","accountingRulesVersion":"accounting-v1",
                 "precisionRulesVersion":"precision-v1","periodStart":"2024-01-01T05:00:00Z",
                 "periodEnd":"2024-02-01T05:00:00Z","marketDataSchemaVersion":"%s","timezone":"America/New_York",
                 "feePolicyId":"%s","buyingPowerBufferPolicyId":"%s"}
                """.formatted(schema, FEE_POLICY, BUFFER_POLICY);
        jdbc.update("insert into backtest.execution_policy_versions "
                        + "(version,policy_artifact_hash,policy_document,locked_at) values (?,?,cast(? as jsonb),?)",
                version, hash, document, NOW.minusSeconds(1800).atOffset(ZoneOffset.UTC));
    }

    private void insertDataset(UUID id, UUID feedId, String layer, String hash) {
        jdbc.update("""
                insert into market_data.dataset_manifests
                    (id, feed_id, data_layer, resolution, revision_number, status, period_start, period_end,
                     schema_version, dataset_hash, created_at, available_at)
                values (?, ?, ?, '30m', 1, 'AVAILABLE', ?, ?, 'market-bars/1', ?, ?, ?)
                """,
                id, feedId, layer,
                OffsetDateTime.parse("2024-01-01T00:00:00Z"), OffsetDateTime.parse("2024-02-01T00:00:00Z"),
                hash, NOW.atOffset(ZoneOffset.UTC), NOW.atOffset(ZoneOffset.UTC));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(StrategyReleaseInputCatalogJooqQueryAdapter.class)
    static class TestApplication {}
}

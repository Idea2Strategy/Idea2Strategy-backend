package com.idea2strategy.backend.persistence.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.application.strategy.StrategyCatalogNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
@SpringBootTest(classes = BasicStrategyCatalogPersistenceIntegrationTest.TestApplication.class)
class BasicStrategyCatalogPersistenceIntegrationTest {
    private static final UUID CATALOG_ID = UUID.fromString("51000000-0000-4000-8000-000000000001");
    private static final UUID RETIRED_CATALOG_ID = UUID.fromString("51000000-0000-4000-8000-000000000002");
    private static final UUID AAPL_ID = UUID.fromString("52000000-0000-4000-8000-000000000001");
    private static final UUID SPY_ID = UUID.fromString("52000000-0000-4000-8000-000000000002");
    private static final UUID DELISTED_ID = UUID.fromString("52000000-0000-4000-8000-000000000003");
    private static final Instant NOW = Instant.parse("2026-08-01T04:00:00Z");

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
    private BasicStrategyCatalogJooqQueryAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedCatalog() {
        jdbcTemplate.update("delete from market_data.feature_definitions");
        jdbcTemplate.update("delete from strategy.element_definitions");
        jdbcTemplate.update("delete from strategy.element_catalog_versions");
        jdbcTemplate.update("delete from market_data.instrument_symbols");
        jdbcTemplate.update("delete from market_data.instruments");

        insertCatalog(CATALOG_ID, "catalog/v1", null, "a".repeat(64));
        insertCatalog(RETIRED_CATALOG_ID, "retired/v1", NOW.minusSeconds(1), "b".repeat(64));
        insertElement("RSI", "INDICATOR", "c".repeat(64));
        insertElement("CONDITION", "CONDITION", "d".repeat(64));
        jdbcTemplate.update(
                """
                insert into market_data.feature_definitions
                    (id, element_catalog_version_id, feature_code, calculator_version, resolution,
                     normalized_parameters, output_value_type, required_history_points, definition_hash, created_at)
                values (?, ?, 'RSI_14', '1.0.0', '1m', cast(? as jsonb), 'NUMBER', 14, ?, ?)
                """,
                UUID.fromString("53000000-0000-4000-8000-000000000001"),
                CATALOG_ID,
                "{\"period\":14}",
                "e".repeat(64),
                NOW.atOffset(ZoneOffset.UTC));

        insertInstrument(AAPL_ID, "STOCK", "XNAS", null, "AAPL");
        insertInstrument(SPY_ID, "ETF", "ARCX", null, "SPY");
        insertInstrument(DELISTED_ID, "STOCK", "XNYS", java.time.LocalDate.of(2026, 7, 31), "OLD");
        jdbcTemplate.update(
                "update market_data.instrument_symbols set effective_to = ? where instrument_id = ?",
                NOW.minusSeconds(1).atOffset(ZoneOffset.UTC),
                DELISTED_ID);
    }

    @Test
    void readsThePublishedCatalogAndHistoricalUsdBacktestInstruments() {
        var service = new BasicStrategyCatalogQueryService(
                adapter, Clock.fixed(NOW, ZoneOffset.UTC), ZoneId.of("America/New_York"));

        var catalog = service.getPublished("basic/v1", "schema/v1", "catalog/v1");

        assertThat(catalog.elements()).extracting("elementCode").containsExactly("CONDITION", "RSI");
        assertThat(catalog.features()).extracting("featureCode").containsExactly("RSI_14");
        assertThat(catalog.instruments()).extracting("symbol").containsExactly("AAPL", "OLD", "SPY");
        assertThat(service.requireElement(CATALOG_ID, "RSI").parameterSchema()).contains("period");
        assertThatThrownBy(() -> service.getPublished("basic/v1", "schema/v1", "retired/v1"))
                .isInstanceOf(StrategyCatalogNotFoundException.class);
    }

    @Test
    void readsSupportedInstrumentsWhenNoCatalogIsPublished() {
        jdbcTemplate.update("delete from market_data.feature_definitions");
        jdbcTemplate.update("delete from strategy.element_definitions");
        jdbcTemplate.update("delete from strategy.element_catalog_versions");
        var service = new BasicStrategyCatalogQueryService(
                adapter, Clock.fixed(NOW, ZoneOffset.UTC), ZoneId.of("America/New_York"));

        assertThat(service.getSupportedInstruments())
                .extracting("symbol")
                .containsExactly("AAPL", "OLD", "SPY");
    }

    private void insertCatalog(UUID id, String catalogVersion, Instant retiredAt, String hash) {
        jdbcTemplate.update(
                """
                insert into strategy.element_catalog_versions
                    (id, language_version, schema_version, catalog_version, data_requirement_version,
                     definition_hash, published_at, retired_at)
                values (?, 'basic/v1', 'schema/v1', ?, 'data/v1', ?, ?, ?)
                """,
                id,
                catalogVersion,
                hash,
                NOW.minusSeconds(60).atOffset(ZoneOffset.UTC),
                retiredAt == null ? null : retiredAt.atOffset(ZoneOffset.UTC));
    }

    private void insertElement(String code, String kind, String hash) {
        jdbcTemplate.update(
                """
                insert into strategy.element_definitions
                    (id, element_catalog_version_id, element_code, element_kind, parameter_schema,
                     input_port_schema, output_port_schema, execution_contract, definition_hash)
                values (?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?)
                """,
                UUID.randomUUID(),
                CATALOG_ID,
                code,
                kind,
                "{\"type\":\"object\",\"properties\":{\"period\":{\"type\":\"integer\"}}}",
                "{}",
                "{}",
                "{\"deterministic\":true}",
                hash);
    }

    private void insertInstrument(
            UUID id, String assetType, String exchangeMic, java.time.LocalDate delistedAt, String symbol) {
        jdbcTemplate.update(
                """
                insert into market_data.instruments
                    (id, asset_type, primary_exchange_mic, currency_code, listed_at, delisted_at, created_at)
                values (?, cast(? as market_data.asset_type), ?, 'USD', ?, ?, ?)
                """,
                id,
                assetType,
                exchangeMic,
                java.time.LocalDate.of(2020, 1, 1),
                delistedAt,
                NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC));
        jdbcTemplate.update(
                """
                insert into market_data.instrument_symbols
                    (id, instrument_id, exchange_mic, symbol, effective_from, effective_to)
                values (?, ?, ?, ?, ?, null)
                """,
                UUID.randomUUID(),
                id,
                exchangeMic,
                symbol,
                NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(BasicStrategyCatalogJooqQueryAdapter.class)
    static class TestApplication {}
}

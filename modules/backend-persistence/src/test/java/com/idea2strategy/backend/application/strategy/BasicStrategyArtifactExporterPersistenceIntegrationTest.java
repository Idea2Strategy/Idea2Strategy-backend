package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.persistence.strategy.BasicStrategyCatalogJooqQueryAdapter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = BasicStrategyArtifactExporterPersistenceIntegrationTest.TestApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BasicStrategyArtifactExporterPersistenceIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-08-31T00:00:00Z");
    private static final UUID AAPL = UUID.fromString("03e7e685-d6da-4f1f-9279-91477884aab9");
    private static final UUID MSFT = UUID.fromString("00000000-0000-4000-8000-000000000302");
    private static final List<UUID> SELECTED_INSTRUMENTS = List.of(
            AAPL,
            MSFT,
            UUID.fromString("00000000-0000-4000-8000-000000000303"),
            UUID.fromString("00000000-0000-4000-8000-000000000304"),
            UUID.fromString("00000000-0000-4000-8000-000000000305"));
    private static final UUID PARTITION = UUID.fromString("71000000-0000-4000-8000-000000000001");

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
    private BasicStrategyCatalogJooqQueryAdapter catalogAdapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void addSelectedInstrumentsToTheMigratedCatalogUniverse() {
        for (int index = 0; index < SELECTED_INSTRUMENTS.size(); index++) {
            insertInstrument(SELECTED_INSTRUMENTS.get(index), "TASK3-" + (index + 1));
        }
    }

    @Test
    void exportsFinalArgumentsFromTheOfficialPersistedExecutionContracts() throws Exception {
        BasicStrategyCatalog catalog = catalog();
        String semantic = semantic(catalog.version().id());

        var plan = new BasicStrategyArtifactExporter().export(
                List.of(new BasicStrategyArtifactExporter.PartitionSource(PARTITION, 10_000, semantic)),
                catalog,
                new BigDecimal("100000"),
                NOW);

        JsonNode root = JSON.readTree(plan.planDocument());
        JsonNode flow = root.path("executionSnapshot").path("partitions").get(0).path("flows").get(0);
        assertThat(root.path("elementCatalogVersion").asText()).isEqualTo("basic-elements:2026-08-25");
        assertThat(flow.path("officialInstrumentIds")).extracting(JsonNode::asText)
                .containsExactly(MSFT.toString(), AAPL.toString());
        assertThat(flow.path("steps").get(0).path("arguments"))
                .isEqualTo(JSON.readTree("{\"operator\":\"GT\",\"reference\":\"PREVIOUS_CLOSE\",\"resolution\":\"30m\"}"));
        assertThat(flow.path("steps").get(1).path("arguments"))
                .isEqualTo(JSON.readTree("{\"operator\":\"GTE\",\"thresholdPercent\":\"10\"}"));
        assertThat(flow.path("steps").get(2).path("arguments"))
                .isEqualTo(JSON.readTree("{\"operator\":\"LT\",\"thresholdPercent\":\"5\"}"));
        assertThat(flow.path("steps").get(3).path("arguments"))
                .isEqualTo(JSON.readTree("{\"allocation\":\"EQUAL\",\"executionMode\":\"1회만\",\"maxExecutions\":\"1\",\"maxPositionPercent\":\"40\",\"orderPercent\":\"25\",\"orderType\":\"MARKET\",\"side\":\"SELL\",\"timeInForce\":\"DAY\",\"waitInterval\":\"1\",\"waitMode\":\"조건 재충족\"}"));
    }

    @Test
    @Transactional
    void persistedRuntimeMappingIsLoadBearingForTheEmittedArtifact() throws Exception {
        jdbc.update("""
                update strategy.element_definitions
                   set execution_contract = jsonb_set(execution_contract, '{runtime,arguments}',
                       '{"operator":"$operator","resolution":"$resolution"}'::jsonb)
                 where element_catalog_version_id = '0f5a0000-0000-4000-8000-000000000001'::uuid
                   and element_code = 'BASIC_PRICE_COMPARE'
                """);
        BasicStrategyCatalog catalog = catalog();

        var plan = new BasicStrategyArtifactExporter().export(
                List.of(new BasicStrategyArtifactExporter.PartitionSource(
                        PARTITION, 10_000, semantic(catalog.version().id()))),
                catalog,
                new BigDecimal("100000"),
                NOW);

        JsonNode clockArguments = JSON.readTree(plan.planDocument())
                .path("executionSnapshot").path("partitions").get(0)
                .path("flows").get(0).path("steps").get(0).path("arguments");
        assertThat(clockArguments.has("reference")).isFalse();
        assertThat(clockArguments.path("operator").asText()).isEqualTo("GT");
        assertThat(clockArguments.path("resolution").asText()).isEqualTo("30m");
    }

    @Test
    void exportsTheRootCompatibilityBundleThroughTheProductionBoundary() throws Exception {
        String inputPath = System.getenv("TASK3_BACKEND_EXPORT_INPUT");
        String outputPath = System.getenv("TASK3_BACKEND_EXPORT_OUTPUT");
        Assumptions.assumeTrue(inputPath != null && outputPath != null);

        JsonNode request = JSON.readTree(Files.readString(Path.of(inputPath)));
        var output = JSON.createObjectNode();
        var cases = output.putArray("cases");
        BasicStrategyCatalog catalog = catalog();
        BasicStrategyArtifactExporter exporter = new BasicStrategyArtifactExporter();
        for (JsonNode requestedCase : request.path("cases")) {
            List<BasicStrategyArtifactExporter.PartitionSource> sources = new java.util.ArrayList<>();
            for (JsonNode partition : requestedCase.path("partitions")) {
                sources.add(new BasicStrategyArtifactExporter.PartitionSource(
                        UUID.fromString(partition.path("key").asText()),
                        partition.path("budgetCapBps").asInt(),
                        JSON.writeValueAsString(partition.path("semanticDocument"))));
            }
            com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease.ContractPlan plan;
            try {
                plan = exporter.export(sources, catalog, new BigDecimal("100000"), NOW);
            } catch (RuntimeException failure) {
                throw new IllegalStateException(
                        "backend export rejected case " + requestedCase.path("name").asText(), failure);
            }
            var item = cases.addObject();
            item.put("name", requestedCase.path("name").asText());
            item.put("planDocument", plan.planDocument());
        }
        Files.writeString(Path.of(outputPath), JSON.writeValueAsString(output));
    }

    private BasicStrategyCatalog catalog() {
        return new BasicStrategyCatalogQueryService(
                catalogAdapter,
                Clock.fixed(NOW, ZoneOffset.UTC),
                ZoneId.of("America/New_York"))
                .getPublished("basic/v1", "basic-semantic/v1", "basic-elements:2026-08-25");
    }

    private static String semantic(UUID catalogId) {
        return StrategyDocumentJson.canonicalize("""
                {"catalogId":"%s","groups":[{
                  "id":"sell:bounds","allocationGroupId":"sell","container":"SELL",
                  "evaluationMode":"INDEPENDENT","allocationMode":"EQUAL",
                  "instrumentIds":["%s","%s"],
                  "blocks":[
                    {"id":"clock","elementCode":"BASIC_PRICE_COMPARE","parameters":{"resolution":"30m","operator":"GT","reference":"PREVIOUS_CLOSE"}},
                    {"id":"lower","elementCode":"BASIC_DRAWDOWN_FROM_PEAK","parameters":{"operator":"GTE","thresholdPercent":"10"}},
                    {"id":"upper","elementCode":"BASIC_DRAWDOWN_FROM_PEAK","parameters":{"operator":"LT","thresholdPercent":"5"}},
                    {"id":"order","elementCode":"BASIC_EQUAL_ALLOCATION_ORDER","parameters":{"orderPercent":"25","maxPositionPercent":"40","executionMode":"1회만","waitMode":"조건 재충족","waitInterval":"1","maxExecutions":"1"}}],
                  "connections":[
                    {"fromBlockId":"clock","outputPort":"passed","toBlockId":"lower","inputPort":"passed"},
                    {"fromBlockId":"lower","outputPort":"passed","toBlockId":"upper","inputPort":"passed"},
                    {"fromBlockId":"upper","outputPort":"passed","toBlockId":"order","inputPort":"passed"}]
                }]}
                """.formatted(catalogId, AAPL, MSFT));
    }

    private void insertInstrument(UUID id, String symbol) {
        jdbc.update("""
                insert into market_data.instruments
                    (id, asset_type, primary_exchange_mic, currency_code, listed_at, created_at)
                values (?, 'STOCK', 'XNAS', 'USD', date '2020-01-01', ?)
                on conflict (id) do nothing
                """, id, NOW.atOffset(ZoneOffset.UTC));
        jdbc.update("""
                insert into market_data.instrument_symbols
                    (id, instrument_id, exchange_mic, symbol, effective_from)
                select ?, ?, 'XNAS', ?, ?
                 where not exists (
                    select 1 from market_data.instrument_symbols where instrument_id = ?)
                """, UUID.nameUUIDFromBytes((id + ":symbol").getBytes()), id, symbol,
                NOW.minusSeconds(3600).atOffset(ZoneOffset.UTC), id);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(BasicStrategyCatalogJooqQueryAdapter.class)
    static class TestApplication {}
}

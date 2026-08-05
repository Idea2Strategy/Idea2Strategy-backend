package com.idea2strategy.backend.api.strategy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryPort;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyFeatureDefinition;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BasicStrategyCatalogControllerTest {
    private static final UUID CATALOG_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID INSTRUMENT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    private StubCatalogPort port;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        port = new StubCatalogPort();
        var service = new BasicStrategyCatalogQueryService(
                port, Clock.fixed(NOW, ZoneOffset.UTC), ZoneId.of("America/New_York"));
        mvc = MockMvcBuilders.standaloneSetup(new BasicStrategyCatalogController(service))
                .setControllerAdvice(new BasicStrategyCatalogExceptionHandler())
                .build();
    }

    @Test
    void exposesThePublishedBasicCatalogAsStableJson() throws Exception {
        mvc.perform(get("/api/v1/strategy-catalogs/basic")
                        .queryParam("languageVersion", "basic/v1")
                        .queryParam("schemaVersion", "schema/v1")
                        .queryParam("catalogVersion", "catalog/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.id").value(CATALOG_ID.toString()))
                .andExpect(jsonPath("$.version.languageVersion").value("basic/v1"))
                .andExpect(jsonPath("$.version.dataRequirementVersion").value("data/v1"))
                .andExpect(jsonPath("$.elements[0].elementCode").value("RSI"))
                .andExpect(jsonPath("$.elements[0].parameterSchema.required[0]").value("period"))
                .andExpect(jsonPath("$.elements[0].executionContract.containers[0]").value("BUY"))
                .andExpect(jsonPath("$.features[0].normalizedParameters.period").value(14))
                .andExpect(jsonPath("$.instruments[0].id").value(INSTRUMENT_ID.toString()))
                .andExpect(jsonPath("$.instruments[0].symbol").value("AAPL"));
    }

    @Test
    void returnsBadRequestForBlankVersionSelectors() throws Exception {
        mvc.perform(get("/api/v1/strategy-catalogs/basic")
                        .queryParam("languageVersion", " ")
                        .queryParam("schemaVersion", "schema/v1")
                        .queryParam("catalogVersion", "catalog/v1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsNotFoundWhenTheSelectedCatalogIsNotPublished() throws Exception {
        port.catalog = Optional.empty();

        mvc.perform(get("/api/v1/strategy-catalogs/basic")
                        .queryParam("languageVersion", "basic/v1")
                        .queryParam("schemaVersion", "schema/v1")
                        .queryParam("catalogVersion", "retired/v1"))
                .andExpect(status().isNotFound());
    }

    private static final class StubCatalogPort implements BasicStrategyCatalogQueryPort {
        private Optional<ElementCatalogVersion> catalog = Optional.of(new ElementCatalogVersion(
                CATALOG_ID, "basic/v1", "schema/v1", "catalog/v1", "data/v1", "a".repeat(64),
                NOW.minusSeconds(60), null));

        @Override
        public Optional<ElementCatalogVersion> findPublishedCatalog(UUID catalogId, Instant at) {
            return catalog.filter(version -> version.id().equals(catalogId));
        }

        @Override
        public Optional<ElementCatalogVersion> findPublishedCatalog(
                String languageVersion, String schemaVersion, String catalogVersion, Instant at) {
            return catalog;
        }

        @Override
        public List<StrategyElementDefinition> findElements(UUID catalogId) {
            return List.of(new StrategyElementDefinition(
                    UUID.randomUUID(), CATALOG_ID, "RSI", "CONDITION",
                    "{\"required\":[\"period\"]}", "{\"input\":{\"type\":\"BOOLEAN\"}}",
                    "{\"result\":{\"type\":\"BOOLEAN\"}}", "{\"containers\":[\"BUY\",\"SELL\"]}",
                    "b".repeat(64)));
        }

        @Override
        public Optional<StrategyElementDefinition> findPublishedElement(
                UUID catalogId, String elementCode, Instant at) {
            return Optional.empty();
        }

        @Override
        public List<StrategyFeatureDefinition> findFeatures(UUID catalogId) {
            return List.of(new StrategyFeatureDefinition(
                    UUID.randomUUID(), CATALOG_ID, "RSI_14", "1.0.0", "1m", "{\"period\":14}",
                    "NUMBER", 14, "c".repeat(64)));
        }

        @Override
        public List<SupportedInstrument> findSupportedInstruments(Instant at, LocalDate marketDate) {
            return List.of(new SupportedInstrument(INSTRUMENT_ID, "STOCK", "XNAS", "USD", "AAPL"));
        }
    }
}

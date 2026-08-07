package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyFeatureDefinition;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicStrategyCatalogQueryServiceTest {
    private static final UUID CATALOG_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T04:00:00Z");

    @Test
    void returnsAnImmutablePublishedCatalogInStableOrder() {
        var port = new StubCatalogPort();
        var service = new BasicStrategyCatalogQueryService(
                port, Clock.fixed(NOW, ZoneId.of("UTC")), ZoneId.of("America/New_York"));

        BasicStrategyCatalog catalog = service.getPublished("basic/v1", "schema/v1", "catalog/v1");

        assertThat(catalog.version().id()).isEqualTo(CATALOG_ID);
        assertThat(catalog.elements()).extracting(StrategyElementDefinition::elementCode)
                .containsExactly("CONDITION", "RSI");
        assertThat(catalog.features()).extracting(StrategyFeatureDefinition::featureCode)
                .containsExactly("RSI_14", "SMA_20");
        assertThat(catalog.instruments()).extracting(SupportedInstrument::symbol)
                .containsExactly("AAPL", "SPY");
        assertThat(catalog.elements()).isUnmodifiable();
        assertThat(port.marketDate).isEqualTo(java.time.LocalDate.of(2026, 8, 1));
    }

    @Test
    void returnsTheLatestCurrentlyPublishedCatalogWithoutClientSideVersionKnowledge() {
        var port = new StubCatalogPort();
        var service = new BasicStrategyCatalogQueryService(
                port, Clock.fixed(NOW, ZoneId.of("UTC")), ZoneId.of("America/New_York"));

        assertThat(service.getLatestPublished().version().id()).isEqualTo(CATALOG_ID);
    }

    @Test
    void returnsSupportedInstrumentsWithoutLoadingAPublishedCatalog() {
        var port = new StubCatalogPort();
        port.catalog = Optional.empty();
        var service = new BasicStrategyCatalogQueryService(
                port, Clock.fixed(NOW, ZoneId.of("UTC")), ZoneId.of("America/New_York"));

        assertThat(service.getSupportedInstruments())
                .extracting(SupportedInstrument::symbol)
                .containsExactly("AAPL", "SPY");
        assertThat(port.marketDate).isEqualTo(java.time.LocalDate.of(2026, 8, 1));
    }

    @Test
    void rejectsUnknownOrCrossCatalogElements() {
        var service = new BasicStrategyCatalogQueryService(
                new StubCatalogPort(), Clock.fixed(NOW, ZoneId.of("UTC")), ZoneId.of("America/New_York"));

        assertThat(service.requireElement(CATALOG_ID, "RSI").elementCode()).isEqualTo("RSI");
        assertThatThrownBy(() -> service.requireElement(CATALOG_ID, "USER_SCRIPT"))
                .isInstanceOf(UnsupportedStrategyElementException.class)
                .hasMessage("Unsupported strategy element: USER_SCRIPT");
        assertThatThrownBy(() -> service.requireElement(UUID.randomUUID(), "RSI"))
                .isInstanceOf(UnsupportedStrategyElementException.class);
    }

    @Test
    void rejectsMissingOrRetiredCatalogVersions() {
        var port = new StubCatalogPort();
        port.catalog = Optional.empty();
        var service = new BasicStrategyCatalogQueryService(
                port, Clock.fixed(NOW, ZoneId.of("UTC")), ZoneId.of("America/New_York"));

        assertThatThrownBy(() -> service.getPublished("basic/v1", "schema/v1", "retired/v1"))
                .isInstanceOf(StrategyCatalogNotFoundException.class);
    }

    private static final class StubCatalogPort implements BasicStrategyCatalogQueryPort {
        private Optional<ElementCatalogVersion> catalog = Optional.of(new ElementCatalogVersion(
                CATALOG_ID,
                "basic/v1",
                "schema/v1",
                "catalog/v1",
                "data/v1",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                NOW.minusSeconds(60),
                null));
        private java.time.LocalDate marketDate;

        @Override
        public Optional<ElementCatalogVersion> findLatestPublishedCatalog(Instant at) {
            return catalog;
        }

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
            return List.of(element("RSI"), element("CONDITION"));
        }

        @Override
        public Optional<StrategyElementDefinition> findPublishedElement(
                UUID catalogId, String elementCode, Instant at) {
            return CATALOG_ID.equals(catalogId) && "RSI".equals(elementCode)
                    ? Optional.of(element("RSI"))
                    : Optional.empty();
        }

        @Override
        public List<StrategyFeatureDefinition> findFeatures(UUID catalogId) {
            return List.of(feature("SMA_20"), feature("RSI_14"));
        }

        @Override
        public List<SupportedInstrument> findSupportedInstruments(Instant at, java.time.LocalDate marketDate) {
            this.marketDate = marketDate;
            return List.of(
                    new SupportedInstrument(UUID.randomUUID(), "ETF", "ARCX", "USD", "SPY"),
                    new SupportedInstrument(UUID.randomUUID(), "STOCK", "XNAS", "USD", "AAPL"));
        }

        private static StrategyElementDefinition element(String code) {
            return new StrategyElementDefinition(
                    UUID.randomUUID(),
                    CATALOG_ID,
                    code,
                    "CONDITION",
                    "{}",
                    "{}",
                    "{}",
                    "{}",
                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        }

        private static StrategyFeatureDefinition feature(String code) {
            return new StrategyFeatureDefinition(
                    UUID.randomUUID(),
                    CATALOG_ID,
                    code,
                    "1.0.0",
                    "1m",
                    "{}",
                    "NUMBER",
                    14,
                    "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
        }
    }
}

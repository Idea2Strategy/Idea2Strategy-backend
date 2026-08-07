package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyFeatureDefinition;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class BasicStrategyCatalogQueryService {
    private final BasicStrategyCatalogQueryPort queryPort;
    private final Clock clock;
    private final ZoneId marketZone;

    public BasicStrategyCatalogQueryService(
            BasicStrategyCatalogQueryPort queryPort, Clock clock, ZoneId marketZone) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.marketZone = Objects.requireNonNull(marketZone, "marketZone");
    }

    public BasicStrategyCatalog getPublished(
            String languageVersion, String schemaVersion, String catalogVersion) {
        Instant now = clock.instant();
        ElementCatalogVersion version = queryPort
                .findPublishedCatalog(languageVersion, schemaVersion, catalogVersion, now)
                .orElseThrow(StrategyCatalogNotFoundException::new);
        return catalog(version, now);
    }

    public BasicStrategyCatalog getLatestPublished() {
        Instant now = clock.instant();
        ElementCatalogVersion version = queryPort
                .findLatestPublishedCatalog(now)
                .orElseThrow(StrategyCatalogNotFoundException::new);
        return catalog(version, now);
    }

    public BasicStrategyCatalog getPublished(UUID catalogId) {
        Objects.requireNonNull(catalogId, "catalogId");
        Instant now = clock.instant();
        ElementCatalogVersion version = queryPort
                .findPublishedCatalog(catalogId, now)
                .orElseThrow(StrategyCatalogNotFoundException::new);
        return catalog(version, now);
    }

    public List<SupportedInstrument> getSupportedInstruments() {
        return supportedInstruments(clock.instant());
    }

    private BasicStrategyCatalog catalog(ElementCatalogVersion version, Instant now) {
        List<StrategyElementDefinition> elements = queryPort.findElements(version.id()).stream()
                .sorted(Comparator.comparing(StrategyElementDefinition::elementCode))
                .toList();
        List<StrategyFeatureDefinition> features = queryPort.findFeatures(version.id()).stream()
                .sorted(Comparator.comparing(StrategyFeatureDefinition::featureCode)
                        .thenComparing(StrategyFeatureDefinition::calculatorVersion)
                        .thenComparing(StrategyFeatureDefinition::resolution))
                .toList();
        List<SupportedInstrument> instruments = supportedInstruments(now);
        return new BasicStrategyCatalog(version, elements, features, instruments);
    }

    private List<SupportedInstrument> supportedInstruments(Instant now) {
        LocalDate marketDate = now.atZone(marketZone).toLocalDate();
        return queryPort.findSupportedInstruments(now, marketDate).stream()
                .sorted(Comparator.comparing(SupportedInstrument::symbol)
                        .thenComparing(SupportedInstrument::primaryExchangeMic))
                .toList();
    }

    public StrategyElementDefinition requireElement(UUID catalogId, String elementCode) {
        Objects.requireNonNull(catalogId, "catalogId");
        Objects.requireNonNull(elementCode, "elementCode");
        return queryPort
                .findPublishedElement(catalogId, elementCode, clock.instant())
                .orElseThrow(() -> new UnsupportedStrategyElementException(elementCode));
    }
}

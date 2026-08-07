package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyFeatureDefinition;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BasicStrategyCatalogQueryPort {
    default Optional<ElementCatalogVersion> findLatestPublishedCatalog(Instant at) {
        return Optional.empty();
    }

    Optional<ElementCatalogVersion> findPublishedCatalog(UUID catalogId, Instant at);

    Optional<ElementCatalogVersion> findPublishedCatalog(
            String languageVersion, String schemaVersion, String catalogVersion, Instant at);

    List<StrategyElementDefinition> findElements(UUID catalogId);

    Optional<StrategyElementDefinition> findPublishedElement(UUID catalogId, String elementCode, Instant at);

    List<StrategyFeatureDefinition> findFeatures(UUID catalogId);

    List<SupportedInstrument> findSupportedInstruments(Instant at, LocalDate marketDate);
}

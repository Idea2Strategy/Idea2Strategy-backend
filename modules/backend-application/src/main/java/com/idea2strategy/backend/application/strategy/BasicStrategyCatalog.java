package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyFeatureDefinition;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.util.List;
import java.util.Objects;

public record BasicStrategyCatalog(
        ElementCatalogVersion version,
        List<StrategyElementDefinition> elements,
        List<StrategyFeatureDefinition> features,
        List<SupportedInstrument> instruments) {
    public BasicStrategyCatalog {
        Objects.requireNonNull(version, "version");
        elements = List.copyOf(elements);
        features = List.copyOf(features);
        instruments = List.copyOf(instruments);
    }
}

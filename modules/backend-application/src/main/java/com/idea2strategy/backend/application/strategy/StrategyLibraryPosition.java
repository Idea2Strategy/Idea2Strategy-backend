package com.idea2strategy.backend.application.strategy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record StrategyLibraryPosition(Instant sortTime, StrategyLibraryItemKind kind, UUID id) {
    public StrategyLibraryPosition {
        Objects.requireNonNull(sortTime, "sortTime");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }
}

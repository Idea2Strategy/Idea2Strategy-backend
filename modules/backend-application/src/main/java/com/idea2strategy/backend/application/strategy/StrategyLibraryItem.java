package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.StrategyMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record StrategyLibraryItem(
        UUID id,
        StrategyLibraryItemKind kind,
        StrategyMode mode,
        String name,
        String description,
        String status,
        String validationStatus,
        String backtestStatus,
        boolean editable,
        Instant updatedAt,
        String version) {

    public StrategyLibraryItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public StrategyLibraryPosition position() {
        return new StrategyLibraryPosition(updatedAt, kind, id);
    }
}

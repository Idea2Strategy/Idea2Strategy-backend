package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.StrategyMode;
import java.time.Instant;
import java.util.List;
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
        String version,
        int blockCount,
        List<String> symbols) {

    public StrategyLibraryItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (blockCount < 0) {
            throw new IllegalArgumentException("blockCount must not be negative");
        }
        symbols = List.copyOf(Objects.requireNonNull(symbols, "symbols"));
    }

    public StrategyLibraryPosition position() {
        return new StrategyLibraryPosition(updatedAt, kind, id);
    }
}

package com.idea2strategy.backend.application.botoperations;

import java.util.Objects;
import java.util.UUID;

public record BotOperationsInstrument(UUID instrumentId, String symbol) {
    public BotOperationsInstrument {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(symbol, "symbol");
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
    }
}

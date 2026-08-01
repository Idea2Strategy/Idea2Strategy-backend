package com.idea2strategy.backend.domain.strategy;

import java.util.Objects;
import java.util.UUID;

public record SupportedInstrument(
        UUID id, String assetType, String primaryExchangeMic, String currencyCode, String symbol) {
    public SupportedInstrument {
        Objects.requireNonNull(id, "id");
        assetType = requireText(assetType, "assetType");
        primaryExchangeMic = requireText(primaryExchangeMic, "primaryExchangeMic");
        currencyCode = requireText(currencyCode, "currencyCode");
        symbol = requireText(symbol, "symbol");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

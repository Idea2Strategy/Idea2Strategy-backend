package com.idea2strategy.backend.application.strategy;

import com.fasterxml.jackson.annotation.JsonValue;

public enum StrategyLibraryItemKind {
    DRAFT("draft"),
    RELEASED("released"),
    PACKAGE("package"),
    TEMPLATE("template");

    private final String wireValue;

    StrategyLibraryItemKind(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    public static StrategyLibraryItemKind fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (var kind : values()) {
            if (kind.wireValue.equalsIgnoreCase(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unsupported strategy library kind");
    }
}

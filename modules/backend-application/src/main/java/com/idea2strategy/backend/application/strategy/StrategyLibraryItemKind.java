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
}

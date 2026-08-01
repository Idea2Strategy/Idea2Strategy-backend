package com.idea2strategy.backend.migration;

import java.util.Arrays;

public enum MigrationOwner {
    BACKEND("backend"),
    TRADING("trading"),
    BACKTEST("backtest"),
    PIPELINE("pipeline"),
    SHARED("shared");

    private final String key;

    MigrationOwner(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    static MigrationOwner fromKey(String key) {
        return Arrays.stream(values())
                .filter(owner -> owner.key.equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown migration owner: " + key));
    }
}

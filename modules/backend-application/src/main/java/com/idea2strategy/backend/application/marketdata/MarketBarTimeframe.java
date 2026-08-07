package com.idea2strategy.backend.application.marketdata;

import java.util.Arrays;

public enum MarketBarTimeframe {
    THIRTY_MINUTES("30m"),
    ONE_HOUR("1h"),
    FOUR_HOURS("4h"),
    ONE_DAY("1d");

    private final String value;

    MarketBarTimeframe(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static MarketBarTimeframe parse(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("timeframe must be one of 30m, 1h, 4h, 1d"));
    }
}

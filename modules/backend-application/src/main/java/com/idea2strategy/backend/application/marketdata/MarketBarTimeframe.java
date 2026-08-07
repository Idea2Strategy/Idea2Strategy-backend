package com.idea2strategy.backend.application.marketdata;

import java.util.Arrays;

public enum MarketBarTimeframe {
    ONE_MINUTE("1m", 1, true),
    FIVE_MINUTES("5m", 5, true),
    FIFTEEN_MINUTES("15m", 15, true),
    THIRTY_MINUTES("30m", 30, false),
    ONE_HOUR("1h", 60, false),
    FOUR_HOURS("4h", 240, false),
    ONE_DAY("1d", 1_440, false);

    private final String value;
    private final int minutes;
    private final boolean displayOnly;

    MarketBarTimeframe(String value, int minutes, boolean displayOnly) {
        this.value = value;
        this.minutes = minutes;
        this.displayOnly = displayOnly;
    }

    public String value() {
        return value;
    }

    public int minutes() {
        return minutes;
    }

    public boolean displayOnly() {
        return displayOnly;
    }

    public static MarketBarTimeframe parse(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "timeframe must be one of 1m, 5m, 15m, 30m, 1h, 4h, 1d"));
    }
}

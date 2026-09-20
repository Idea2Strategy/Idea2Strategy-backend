package com.idea2strategy.backend.application.marketdata;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

public enum MarketBarWindow {
    ONE_MONTH("1m", 1),
    THREE_MONTHS("3m", 3);

    private final String value;
    private final int months;

    MarketBarWindow(String value, int months) {
        this.value = value;
        this.months = months;
    }

    public String value() {
        return value;
    }

    public Instant startAt(Instant latest) {
        return latest.atZone(ZoneOffset.UTC).minusMonths(months).toInstant();
    }

    public static MarketBarWindow parse(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("window must be one of 1m, 3m"));
    }
}

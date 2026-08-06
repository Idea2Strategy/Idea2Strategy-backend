package com.idea2strategy.backend.application.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MarketBar(
        String eventId,
        UUID instrumentId,
        String provider,
        String feed,
        Instant occurredAt,
        long sequence,
        int revision,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume) {
    public MarketBar {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(feed, "feed");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(open, "open");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(volume, "volume");
        if (sequence < 0 || revision < 0) {
            throw new IllegalArgumentException("sequence and revision must be non-negative");
        }
    }
}

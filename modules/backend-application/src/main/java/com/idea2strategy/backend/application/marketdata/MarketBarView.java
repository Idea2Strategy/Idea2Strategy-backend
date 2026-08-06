package com.idea2strategy.backend.application.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MarketBarView(
        String eventId,
        UUID instrumentId,
        String symbol,
        String provider,
        String feed,
        Instant occurredAt,
        long sequence,
        int revision,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume) {}

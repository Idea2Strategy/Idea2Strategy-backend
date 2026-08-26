package com.idea2strategy.backend.application.backtest;

import java.time.LocalDate;
import java.util.UUID;

public record CustomBacktestCommand(
        UUID botId,
        LocalDate periodStart,
        LocalDate periodEnd,
        String idempotencyKey) {
    public CustomBacktestCommand {
        if (botId == null) throw new IllegalArgumentException("botId must not be null");
        if (periodStart == null) throw new IllegalArgumentException("periodStart must not be null");
        if (periodEnd == null) throw new IllegalArgumentException("periodEnd must not be null");
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("periodEnd must not precede periodStart");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }
}

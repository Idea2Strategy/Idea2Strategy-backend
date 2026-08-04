package com.idea2strategy.backend.application.backtest;

import java.util.Objects;
import java.util.UUID;

public record BacktestRequestReceipt(UUID messageId, String eventType, boolean created) {
    public BacktestRequestReceipt {
        Objects.requireNonNull(messageId, "messageId");
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
    }
}

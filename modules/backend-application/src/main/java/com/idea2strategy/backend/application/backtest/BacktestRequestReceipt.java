package com.idea2strategy.backend.application.backtest;

import java.util.Objects;
import java.util.UUID;

public record BacktestRequestReceipt(UUID messageId, String eventType, boolean created, UUID runId) {
    public BacktestRequestReceipt(UUID messageId, String eventType, boolean created) {
        this(messageId, eventType, created, messageId);
    }

    public BacktestRequestReceipt {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(runId, "runId");
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
    }
}

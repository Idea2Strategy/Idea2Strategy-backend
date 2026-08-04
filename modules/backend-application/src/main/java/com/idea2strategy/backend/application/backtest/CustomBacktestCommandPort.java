package com.idea2strategy.backend.application.backtest;

import java.time.Instant;
import java.util.UUID;

public interface CustomBacktestCommandPort {
    BacktestRequestReceipt enqueue(UUID accountId, CustomBacktestCommand command, Instant occurredAt);
}

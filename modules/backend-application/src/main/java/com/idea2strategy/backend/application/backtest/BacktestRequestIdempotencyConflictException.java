package com.idea2strategy.backend.application.backtest;

public final class BacktestRequestIdempotencyConflictException extends RuntimeException {
    public BacktestRequestIdempotencyConflictException() {
        super("The backtest idempotency key was already used for another request");
    }
}

package com.idea2strategy.backend.application.strategy;

public final class StrategyEditLeaseInvalidException extends RuntimeException {
    public StrategyEditLeaseInvalidException() {
        super("Strategy edit lease is invalid or expired");
    }
}

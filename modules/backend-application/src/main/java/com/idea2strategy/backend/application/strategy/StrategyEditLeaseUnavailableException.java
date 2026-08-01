package com.idea2strategy.backend.application.strategy;

public final class StrategyEditLeaseUnavailableException extends RuntimeException {
    public StrategyEditLeaseUnavailableException() {
        super("Strategy is already being edited");
    }
}

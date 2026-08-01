package com.idea2strategy.backend.application.strategy;

public final class ImmutableStrategyReleaseRejectedException extends RuntimeException {
    public ImmutableStrategyReleaseRejectedException(String message) {
        super(message);
    }
}

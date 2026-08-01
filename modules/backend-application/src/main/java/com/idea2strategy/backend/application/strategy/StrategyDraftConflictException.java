package com.idea2strategy.backend.application.strategy;

public final class StrategyDraftConflictException extends RuntimeException {
    public StrategyDraftConflictException() {
        super("Strategy draft changed; reload before saving");
    }
}

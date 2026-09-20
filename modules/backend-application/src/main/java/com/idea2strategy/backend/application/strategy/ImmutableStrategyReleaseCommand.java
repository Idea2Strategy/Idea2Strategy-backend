package com.idea2strategy.backend.application.strategy;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record ImmutableStrategyReleaseCommand(
        UUID releaseId,
        BigDecimal initialCashAmount,
        int budgetCapBps,
        String candidateConflictPolicy) {
    public ImmutableStrategyReleaseCommand {
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(initialCashAmount, "initialCashAmount");
        candidateConflictPolicy = StrategyDocumentJson.canonicalize(candidateConflictPolicy);
    }
}

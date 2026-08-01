package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.domain.strategy.StrategyValidationFreshness;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public final class StrategyValidationQueryService {
    private final StrategyValidationRunQueryPort validationQueryPort;
    private final StrategyDocumentQueryPort documentQueryPort;
    private final CurrentPrincipal principal;

    public StrategyValidationQueryService(
            StrategyValidationRunQueryPort validationQueryPort,
            StrategyDocumentQueryPort documentQueryPort,
            CurrentPrincipal principal) {
        this.validationQueryPort = Objects.requireNonNull(validationQueryPort, "validationQueryPort");
        this.documentQueryPort = Objects.requireNonNull(documentQueryPort, "documentQueryPort");
        this.principal = Objects.requireNonNull(principal, "principal");
    }

    public StrategyValidationSnapshot getOwned(UUID validationRunId) {
        var run = validationQueryPort.findOwnedById(validationRunId, principal.accountId())
                .orElseThrow(() -> new NoSuchElementException("Strategy validation not found"));
        var document = documentQueryPort.findOwnedByStrategyId(run.strategyId(), principal.accountId())
                .orElseThrow(() -> new NoSuchElementException("Strategy document not found"));
        var freshness = run.semanticHash().equals(document.semanticHash())
                ? StrategyValidationFreshness.CURRENT
                : StrategyValidationFreshness.REVALIDATION_REQUIRED;
        return new StrategyValidationSnapshot(run, freshness);
    }
}

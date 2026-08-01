package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.common.IdGenerator;
import com.idea2strategy.backend.domain.strategy.CompiledFlowPlan;
import com.idea2strategy.backend.domain.strategy.StrategyMode;
import com.idea2strategy.backend.domain.strategy.StrategyValidationStatus;
import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public final class BasicExecutionPlanCommandService {
    private final CompiledFlowPlanCommandPort planPort;
    private final StrategyValidationRunQueryPort validationPort;
    private final StrategyQueryPort strategyPort;
    private final StrategyDocumentQueryPort documentPort;
    private final CurrentPrincipal principal;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final BasicExecutionPlanCompiler compiler;

    public BasicExecutionPlanCommandService(
            CompiledFlowPlanCommandPort planPort,
            StrategyValidationRunQueryPort validationPort,
            StrategyQueryPort strategyPort,
            StrategyDocumentQueryPort documentPort,
            CurrentPrincipal principal,
            IdGenerator idGenerator,
            Clock clock) {
        this.planPort = Objects.requireNonNull(planPort, "planPort");
        this.validationPort = Objects.requireNonNull(validationPort, "validationPort");
        this.strategyPort = Objects.requireNonNull(strategyPort, "strategyPort");
        this.documentPort = Objects.requireNonNull(documentPort, "documentPort");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.compiler = new BasicExecutionPlanCompiler();
    }

    public CompiledFlowPlan compile(UUID validationRunId, BasicStrategyCatalog catalog) {
        Objects.requireNonNull(validationRunId, "validationRunId");
        Objects.requireNonNull(catalog, "catalog");
        UUID accountId = principal.accountId();
        var validation = validationPort.findOwnedById(validationRunId, accountId)
                .orElseThrow(() -> new NoSuchElementException("Strategy validation not found"));
        if (validation.status() != StrategyValidationStatus.VALID) {
            throw new IllegalStateException("Strategy validation must be VALID");
        }
        var strategy = strategyPort.findOwnedById(validation.strategyId(), accountId)
                .orElseThrow(() -> new NoSuchElementException("Strategy not found"));
        if (strategy.mode() != StrategyMode.BASIC) {
            throw new IllegalArgumentException("Strategy must use BASIC mode");
        }
        var document = documentPort.findOwnedByStrategyId(validation.strategyId(), accountId)
                .orElseThrow(() -> new NoSuchElementException("Strategy document not found"));
        if (!validation.semanticHash().equals(document.semanticHash())
                || validation.requestedEditSequence() != document.editSequence()) {
            throw new IllegalStateException("Strategy validation is stale");
        }
        if (!validation.elementCatalogVersionId().equals(catalog.version().id())) {
            throw new IllegalStateException("Strategy validation catalog does not match");
        }

        var candidate = compiler.compile(
                idGenerator.nextId(), document, catalog, clock.instant());
        return planPort.saveOrFind(candidate);
    }
}

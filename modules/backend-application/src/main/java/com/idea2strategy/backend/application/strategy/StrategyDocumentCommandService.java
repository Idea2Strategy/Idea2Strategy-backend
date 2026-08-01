package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public final class StrategyDocumentCommandService {
    private final StrategyDocumentCommandPort commandPort;
    private final StrategyDocumentQueryPort documentQueryPort;
    private final StrategyQueryPort strategyQueryPort;
    private final CurrentPrincipal principal;
    private final Clock clock;

    public StrategyDocumentCommandService(
            StrategyDocumentCommandPort commandPort,
            StrategyDocumentQueryPort documentQueryPort,
            StrategyQueryPort strategyQueryPort,
            CurrentPrincipal principal,
            Clock clock) {
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.documentQueryPort = Objects.requireNonNull(documentQueryPort, "documentQueryPort");
        this.strategyQueryPort = Objects.requireNonNull(strategyQueryPort, "strategyQueryPort");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StrategyDocument save(
            UUID strategyId,
            String semanticDocument,
            String presentationDocument,
            String semanticSchemaVersion,
            String presentationSchemaVersion) {
        UUID ownerAccountId = principal.accountId();
        strategyQueryPort
                .findOwnedById(strategyId, ownerAccountId)
                .orElseThrow(() -> new NoSuchElementException("Strategy not found"));

        String canonicalSemantic = StrategyDocumentJson.canonicalize(semanticDocument);
        String canonicalPresentation = StrategyDocumentJson.canonicalize(presentationDocument);
        Instant now = clock.instant();
        StrategyDocument document = documentQueryPort
                .findOwnedByStrategyId(strategyId, ownerAccountId)
                .map(current -> current.replace(
                        canonicalSemantic,
                        canonicalPresentation,
                        semanticSchemaVersion,
                        presentationSchemaVersion,
                        StrategyDocumentJson.sha256(canonicalSemantic),
                        StrategyDocumentJson.sha256(canonicalPresentation),
                        now))
                .orElseGet(() -> StrategyDocument.create(
                        strategyId,
                        canonicalSemantic,
                        canonicalPresentation,
                        semanticSchemaVersion,
                        presentationSchemaVersion,
                        StrategyDocumentJson.sha256(canonicalSemantic),
                        StrategyDocumentJson.sha256(canonicalPresentation),
                        now));
        commandPort.save(document);
        return document;
    }

}

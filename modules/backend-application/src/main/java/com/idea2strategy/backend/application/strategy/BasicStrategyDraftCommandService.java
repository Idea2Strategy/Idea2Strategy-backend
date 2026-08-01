package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.common.DomainEventPublisher;
import com.idea2strategy.backend.application.common.IdGenerator;
import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyCreated;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import com.idea2strategy.backend.domain.strategy.StrategyMode;
import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public final class BasicStrategyDraftCommandService {
    public static final String SEMANTIC_SCHEMA_VERSION = "basic-semantic/v1";
    public static final String PRESENTATION_SCHEMA_VERSION = "basic-presentation/v1";
    private static final String EMPTY_SEMANTIC_DOCUMENT = "{\"groups\":[],\"mode\":\"BASIC\"}";
    private static final String EMPTY_PRESENTATION_DOCUMENT =
            "{\"positions\":{},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}";

    private final BasicStrategyDraftCommandPort commandPort;
    private final StrategyQueryPort strategyQueryPort;
    private final StrategyDocumentQueryPort documentQueryPort;
    private final CurrentPrincipal principal;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final DomainEventPublisher eventPublisher;

    public BasicStrategyDraftCommandService(
            BasicStrategyDraftCommandPort commandPort,
            StrategyQueryPort strategyQueryPort,
            StrategyDocumentQueryPort documentQueryPort,
            CurrentPrincipal principal,
            IdGenerator idGenerator,
            Clock clock,
            DomainEventPublisher eventPublisher) {
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.strategyQueryPort = Objects.requireNonNull(strategyQueryPort, "strategyQueryPort");
        this.documentQueryPort = Objects.requireNonNull(documentQueryPort, "documentQueryPort");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    public UUID createBasic(String name, String description) {
        Instant now = clock.instant();
        Strategy strategy = Strategy.createBasic(idGenerator.nextId(), principal.accountId(), name, description, now);
        StrategyDocument document = StrategyDocument.create(
                strategy.id(),
                EMPTY_SEMANTIC_DOCUMENT,
                EMPTY_PRESENTATION_DOCUMENT,
                SEMANTIC_SCHEMA_VERSION,
                PRESENTATION_SCHEMA_VERSION,
                StrategyDocumentJson.sha256(EMPTY_SEMANTIC_DOCUMENT),
                StrategyDocumentJson.sha256(EMPTY_PRESENTATION_DOCUMENT),
                now);
        commandPort.create(strategy, document);
        eventPublisher.publish(new StrategyCreated(strategy.id(), strategy.ownerAccountId(), strategy.mode(), now));
        return strategy.id();
    }

    public StrategyDocument autosave(
            UUID strategyId,
            long expectedEditSequence,
            String semanticDocument,
            String presentationDocument,
            String semanticSchemaVersion,
            String presentationSchemaVersion) {
        return save(
                strategyId,
                expectedEditSequence,
                semanticDocument,
                presentationDocument,
                semanticSchemaVersion,
                presentationSchemaVersion);
    }

    public StrategyDocument saveExplicitly(
            UUID strategyId,
            long expectedEditSequence,
            String semanticDocument,
            String presentationDocument,
            String semanticSchemaVersion,
            String presentationSchemaVersion) {
        return save(
                strategyId,
                expectedEditSequence,
                semanticDocument,
                presentationDocument,
                semanticSchemaVersion,
                presentationSchemaVersion);
    }

    private StrategyDocument save(
            UUID strategyId,
            long expectedEditSequence,
            String semanticDocument,
            String presentationDocument,
            String semanticSchemaVersion,
            String presentationSchemaVersion) {
        UUID ownerAccountId = principal.accountId();
        Strategy strategy = strategyQueryPort
                .findOwnedById(strategyId, ownerAccountId)
                .orElseThrow(() -> new NoSuchElementException("Strategy not found"));
        if (strategy.mode() != StrategyMode.BASIC) {
            throw new IllegalArgumentException("Strategy must use BASIC mode");
        }

        StrategyDocument current = documentQueryPort
                .findOwnedByStrategyId(strategyId, ownerAccountId)
                .orElseThrow(() -> new NoSuchElementException("Strategy document not found"));
        if (current.editSequence() != expectedEditSequence) {
            throw new StrategyDraftConflictException();
        }

        String canonicalSemantic = StrategyDocumentJson.canonicalize(semanticDocument);
        String canonicalPresentation = StrategyDocumentJson.canonicalize(presentationDocument);
        StrategyDocument replacement = current.replace(
                canonicalSemantic,
                canonicalPresentation,
                semanticSchemaVersion,
                presentationSchemaVersion,
                StrategyDocumentJson.sha256(canonicalSemantic),
                StrategyDocumentJson.sha256(canonicalPresentation),
                clock.instant());
        if (!commandPort.replaceDocument(replacement, expectedEditSequence)) {
            throw new StrategyDraftConflictException();
        }
        return replacement;
    }
}

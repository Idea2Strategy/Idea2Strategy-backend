package com.idea2strategy.backend.application.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

public final class StrategyCopyCommandService {
    private static final String BASIC_SEMANTIC_SCHEMA_VERSION = "basic-semantic/v1";
    private static final String BASIC_PRESENTATION_SCHEMA_VERSION = "basic-presentation/v1";
    private static final String EMPTY_PRESENTATION_DOCUMENT =
            "{\"positions\":{},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}";

    private final BasicStrategyDraftCommandPort commandPort;
    private final StrategyQueryPort strategyQueryPort;
    private final StrategyDocumentQueryPort documentQueryPort;
    private final BasicStructureCatalogQueryService structureCatalogService;
    private final CurrentPrincipal principal;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final DomainEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public StrategyCopyCommandService(
            BasicStrategyDraftCommandPort commandPort,
            StrategyQueryPort strategyQueryPort,
            StrategyDocumentQueryPort documentQueryPort,
            BasicStructureCatalogQueryService structureCatalogService,
            CurrentPrincipal principal,
            IdGenerator idGenerator,
            Clock clock,
            DomainEventPublisher eventPublisher) {
        this(
                commandPort,
                strategyQueryPort,
                documentQueryPort,
                structureCatalogService,
                principal,
                idGenerator,
                clock,
                eventPublisher,
                new ObjectMapper());
    }

    StrategyCopyCommandService(
            BasicStrategyDraftCommandPort commandPort,
            StrategyQueryPort strategyQueryPort,
            StrategyDocumentQueryPort documentQueryPort,
            BasicStructureCatalogQueryService structureCatalogService,
            CurrentPrincipal principal,
            IdGenerator idGenerator,
            Clock clock,
            DomainEventPublisher eventPublisher,
            ObjectMapper objectMapper) {
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.strategyQueryPort = Objects.requireNonNull(strategyQueryPort, "strategyQueryPort");
        this.documentQueryPort = Objects.requireNonNull(documentQueryPort, "documentQueryPort");
        this.structureCatalogService = Objects.requireNonNull(structureCatalogService, "structureCatalogService");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public UUID copyOwnedStrategy(UUID sourceStrategyId) {
        UUID ownerAccountId = principal.accountId();
        Strategy source = strategyQueryPort
                .findOwnedById(Objects.requireNonNull(sourceStrategyId, "sourceStrategyId"), ownerAccountId)
                .orElseThrow(() -> new NoSuchElementException("Strategy not found"));
        StrategyDocument sourceDocument = documentQueryPort
                .findOwnedByStrategyId(sourceStrategyId, ownerAccountId)
                .orElseThrow(() -> new NoSuchElementException("Strategy document not found"));

        String semanticDocument = StrategyDocumentJson.canonicalize(sourceDocument.semanticDocument());
        String presentationDocument = StrategyDocumentJson.canonicalize(sourceDocument.presentationDocument());
        return createDraft(
                source.mode(),
                source.name(),
                source.description(),
                semanticDocument,
                presentationDocument,
                sourceDocument.semanticSchemaVersion(),
                sourceDocument.presentationSchemaVersion());
    }

    public UUID copyBasicPackage(
            BasicStrategyCatalog catalog,
            UUID packageVersionId,
            String name,
            String description) {
        Objects.requireNonNull(catalog, "catalog");
        BasicStructureVersion structure = structureCatalogService.getPublished(catalog).stream()
                .filter(candidate -> candidate.id().equals(packageVersionId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Basic structure version not found"));
        if (structure.kind() != BasicStructureKind.PACKAGE) {
            throw new IllegalArgumentException("Basic structure must be a PACKAGE");
        }
        return createDraft(
                StrategyMode.BASIC,
                name,
                description,
                packageSemanticDocument(structure),
                EMPTY_PRESENTATION_DOCUMENT,
                BASIC_SEMANTIC_SCHEMA_VERSION,
                BASIC_PRESENTATION_SCHEMA_VERSION);
    }

    private UUID createDraft(
            StrategyMode mode,
            String name,
            String description,
            String semanticDocument,
            String presentationDocument,
            String semanticSchemaVersion,
            String presentationSchemaVersion) {
        UUID strategyId = idGenerator.nextId();
        UUID ownerAccountId = principal.accountId();
        Instant now = clock.instant();
        Strategy strategy = new Strategy(
                strategyId,
                ownerAccountId,
                mode,
                name,
                description,
                0,
                now,
                now);
        StrategyDocument document = StrategyDocument.create(
                strategyId,
                semanticDocument,
                presentationDocument,
                semanticSchemaVersion,
                presentationSchemaVersion,
                StrategyDocumentJson.sha256(semanticDocument),
                StrategyDocumentJson.sha256(presentationDocument),
                now);
        commandPort.create(strategy, document);
        eventPublisher.publish(new StrategyCreated(strategyId, ownerAccountId, mode, now));
        return strategyId;
    }

    private String packageSemanticDocument(BasicStructureVersion structure) {
        try {
            JsonNode source = objectMapper.readTree(structure.flowDocument());
            ObjectNode group = objectMapper.createObjectNode();
            group.put("id", "group-1");
            group.set("container", source.path("container").deepCopy());
            group.put("evaluationMode", "INDEPENDENT");
            group.put("allocationMode", "EQUAL");
            group.set("instrumentIds", source.path("instrumentIds").deepCopy());
            group.set("blocks", source.path("blocks").deepCopy());
            group.set("connections", source.path("connections").deepCopy());

            ObjectNode document = objectMapper.createObjectNode();
            document.put("mode", "BASIC");
            document.put("catalogId", structure.elementCatalogVersionId().toString());
            document.putArray("groups").add(group);
            return StrategyDocumentJson.canonicalize(objectMapper.writeValueAsString(document));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Basic package flow document must be valid JSON", exception);
        }
    }
}

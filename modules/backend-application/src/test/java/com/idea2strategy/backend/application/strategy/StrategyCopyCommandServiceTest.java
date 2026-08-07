package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.testing.FixedIdGenerator;
import com.idea2strategy.backend.application.testing.RecordingDomainEventPublisher;
import com.idea2strategy.backend.application.testing.TestSessionPrincipal;
import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyCreated;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StrategyCopyCommandServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID SOURCE_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID COPY_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID CATALOG_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID PACKAGE_VERSION_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T08:30:00Z");

    @Test
    void copiesAnOwnedStrategyIntoAnIndependentDraft() {
        var repository = new InMemoryRepository();
        var source = new Strategy(
                SOURCE_ID, OWNER_ID, StrategyMode.BASIC, "Momentum", "Original", 7,
                NOW.minusSeconds(3600), NOW.minusSeconds(60));
        var sourceDocument = StrategyDocument.create(
                        SOURCE_ID,
                        "{\"groups\":[{\"id\":\"buy\"}],\"mode\":\"BASIC\"}",
                        "{\"positions\":{\"buy\":{\"x\":10}},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}",
                        "basic-semantic/v1",
                        "basic-presentation/v1",
                        StrategyDocumentJson.sha256("{\"groups\":[{\"id\":\"buy\"}],\"mode\":\"BASIC\"}"),
                        StrategyDocumentJson.sha256("{\"positions\":{\"buy\":{\"x\":10}},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}"),
                        NOW.minusSeconds(3600))
                .replace(
                        "{\"groups\":[{\"id\":\"buy\"}],\"mode\":\"BASIC\"}",
                        "{\"positions\":{\"buy\":{\"x\":10}},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}",
                        "basic-semantic/v1",
                        "basic-presentation/v1",
                        StrategyDocumentJson.sha256("{\"groups\":[{\"id\":\"buy\"}],\"mode\":\"BASIC\"}"),
                        StrategyDocumentJson.sha256("{\"positions\":{\"buy\":{\"x\":10}},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}"),
                        NOW.minusSeconds(60));
        repository.create(source, sourceDocument);
        var events = new RecordingDomainEventPublisher();
        var service = service(repository, events, List.of());

        UUID copiedId = service.copyOwnedStrategy(SOURCE_ID);

        Strategy copied = repository.strategies.get(copiedId);
        StrategyDocument copiedDocument = repository.documents.get(copiedId);
        assertThat(copiedId).isEqualTo(COPY_ID).isNotEqualTo(SOURCE_ID);
        assertThat(copied.ownerAccountId()).isEqualTo(OWNER_ID);
        assertThat(copied.mode()).isEqualTo(source.mode());
        assertThat(copied.name()).isEqualTo(source.name());
        assertThat(copied.description()).isEqualTo(source.description());
        assertThat(copied.editSequence()).isZero();
        assertThat(copiedDocument.strategyId()).isEqualTo(COPY_ID);
        assertThat(copiedDocument.semanticDocument()).isEqualTo(sourceDocument.semanticDocument());
        assertThat(copiedDocument.presentationDocument()).isEqualTo(sourceDocument.presentationDocument());
        assertThat(copiedDocument.editSequence()).isZero();
        assertThat(repository.strategies.get(SOURCE_ID)).isEqualTo(source);
        assertThat(repository.documents.get(SOURCE_ID)).isEqualTo(sourceDocument);
        assertThat(events.publishedEvents()).containsExactly(
                new StrategyCreated(COPY_ID, OWNER_ID, StrategyMode.BASIC, NOW));
    }

    @Test
    void refusesToCopyAnotherOwnersStrategy() {
        var repository = new InMemoryRepository();
        repository.create(
                Strategy.createBasic(SOURCE_ID, OTHER_OWNER_ID, "Private", null, NOW),
                document(SOURCE_ID, "{\"groups\":[],\"mode\":\"BASIC\"}"));
        var service = service(repository, new RecordingDomainEventPublisher(), List.of());

        assertThatThrownBy(() -> service.copyOwnedStrategy(SOURCE_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Strategy not found");
        assertThat(repository.strategies).doesNotContainKey(COPY_ID);
    }

    @Test
    void refusesToCopyAProStrategyWhileProAccessIsClosed() {
        var repository = new InMemoryRepository();
        repository.create(
                new Strategy(SOURCE_ID, OWNER_ID, StrategyMode.PRO, "Pro draft", null, 0, NOW, NOW),
                document(SOURCE_ID, "{\"groups\":[],\"mode\":\"PRO\"}"));
        var service = service(repository, new RecordingDomainEventPublisher(), List.of());

        assertThatThrownBy(() -> service.copyOwnedStrategy(SOURCE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only BASIC strategies can be copied");
        assertThat(repository.strategies).doesNotContainKey(COPY_ID);
    }

    @Test
    void copiesAnOfficialBasicPackageWithoutRetainingPackageLineage() {
        var repository = new InMemoryRepository();
        List<BasicStructureCandidate> structures = structures();
        var service = service(repository, new RecordingDomainEventPublisher(), structures);

        UUID copiedId = service.copyBasicPackage(catalog(), PACKAGE_VERSION_ID, "RSI structure", "Editable copy");

        Strategy copied = repository.strategies.get(copiedId);
        StrategyDocument document = repository.documents.get(copiedId);
        assertThat(copied.mode()).isEqualTo(StrategyMode.BASIC);
        assertThat(copied.name()).isEqualTo("RSI structure");
        assertThat(document.editSequence()).isZero();
        assertThat(document.semanticDocument()).isEqualTo(StrategyDocumentJson.canonicalize("""
                {
                  "mode":"BASIC",
                  "catalogId":"30000000-0000-4000-8000-000000000001",
                  "groups":[{
                    "id":"group-1",
                    "container":"BUY",
                    "evaluationMode":"INDEPENDENT",
                    "allocationMode":"EQUAL",
                    "instrumentIds":[],
                    "blocks":[{"id":"indicator","elementCode":"RSI","parameters":{"period":null}}],
                    "connections":[]
                  }]
                }
                """));
        assertThat(document.semanticDocument())
                .doesNotContain(PACKAGE_VERSION_ID.toString())
                .doesNotContain("RSI_PACKAGE")
                .doesNotContain("packageId");
        assertThat(repository.runtimeWrites).isEmpty();
    }

    @Test
    void refusesToCopyATemplateAsAPackage() {
        var repository = new InMemoryRepository();
        var service = service(repository, new RecordingDomainEventPublisher(), structures());
        UUID buyTemplateId = structures().getFirst().id();

        assertThatThrownBy(() -> service.copyBasicPackage(catalog(), buyTemplateId, "Not a package", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Basic structure must be a PACKAGE");
        assertThat(repository.strategies).isEmpty();
    }

    private static StrategyCopyCommandService service(
            InMemoryRepository repository,
            RecordingDomainEventPublisher events,
            List<BasicStructureCandidate> structures) {
        var structureService = new BasicStructureCatalogQueryService(
                (catalogId, publishedAt) -> structures,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new StrategyCopyCommandService(
                repository,
                repository,
                repository,
                structureService,
                new TestSessionPrincipal(OWNER_ID),
                new FixedIdGenerator(COPY_ID),
                Clock.fixed(NOW, ZoneOffset.UTC),
                events);
    }

    private static StrategyDocument document(UUID strategyId, String semanticDocument) {
        String presentation = "{\"positions\":{},\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}";
        return StrategyDocument.create(
                strategyId,
                semanticDocument,
                presentation,
                "basic-semantic/v1",
                "basic-presentation/v1",
                StrategyDocumentJson.sha256(semanticDocument),
                StrategyDocumentJson.sha256(presentation),
                NOW);
    }

    private static BasicStrategyCatalog catalog() {
        return new BasicStrategyCatalog(
                new ElementCatalogVersion(
                        CATALOG_ID,
                        "basic/v1",
                        "schema/v1",
                        "catalog/v1",
                        "data/v1",
                        "a".repeat(64),
                        NOW.minusSeconds(60),
                        null),
                List.of(new StrategyElementDefinition(
                        UUID.randomUUID(), CATALOG_ID, "RSI", "CONDITION", "{}", "{}", "{}", "{}", "b".repeat(64))),
                List.of(),
                List.of());
    }

    private static List<BasicStructureCandidate> structures() {
        return List.of(
                structure(UUID.fromString("40000000-0000-4000-8000-000000000010"), "BUY_TEMPLATE", "BUY"),
                structure(UUID.fromString("40000000-0000-4000-8000-000000000011"), "SELL_TEMPLATE", "SELL"),
                structure(PACKAGE_VERSION_ID, "PACKAGE", "BUY"));
    }

    private static BasicStructureCandidate structure(UUID id, String kind, String container) {
        String code = kind.equals("PACKAGE") ? "RSI_PACKAGE" : kind;
        String flow = StrategyDocumentJson.canonicalize(
                "{\"mode\":\"BASIC\",\"kind\":\"" + kind + "\",\"container\":\"" + container + "\","
                        + "\"instrumentIds\":[],\"blocks\":[{\"id\":\"indicator\","
                        + "\"elementCode\":\"RSI\",\"parameters\":{\"period\":null}}],\"connections\":[]}");
        return new BasicStructureCandidate(
                id,
                UUID.randomUUID(),
                code,
                "1.0.0",
                CATALOG_ID,
                "{\"ko\":\"구조\",\"en\":\"Structure\"}",
                "{\"ko\":\"설명\",\"en\":\"Description\"}",
                flow,
                StrategyDocumentJson.sha256(flow),
                NOW.minusSeconds(30));
    }

    private static final class InMemoryRepository
            implements BasicStrategyDraftCommandPort, StrategyQueryPort, StrategyDocumentQueryPort {
        private final Map<UUID, Strategy> strategies = new HashMap<>();
        private final Map<UUID, StrategyDocument> documents = new HashMap<>();
        private final List<String> runtimeWrites = new ArrayList<>();

        @Override
        public void create(Strategy strategy, StrategyDocument document) {
            strategies.put(strategy.id(), strategy);
            documents.put(document.strategyId(), document);
        }

        @Override
        public StrategyDraftReplaceResult replaceDocument(
                StrategyDocument document,
                long expectedEditSequence,
                UUID sessionId,
                String leaseTokenDigest,
                Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Strategy> findOwnedById(UUID strategyId, UUID ownerAccountId) {
            return Optional.ofNullable(strategies.get(strategyId))
                    .filter(strategy -> strategy.ownerAccountId().equals(ownerAccountId));
        }

        @Override
        public Optional<StrategyDocument> findOwnedByStrategyId(UUID strategyId, UUID ownerAccountId) {
            return findOwnedById(strategyId, ownerAccountId).map(strategy -> documents.get(strategy.id()));
        }
    }
}

package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.strategy.BacktestDataCoverage.FeedResolution;
import com.idea2strategy.backend.application.testing.FixedIdGenerator;
import com.idea2strategy.backend.application.testing.TestPrincipal;
import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyFeatureDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyValidationFinding;
import com.idea2strategy.backend.domain.strategy.StrategyValidationFreshness;
import com.idea2strategy.backend.domain.strategy.StrategyValidationRun;
import com.idea2strategy.backend.domain.strategy.StrategyValidationStatus;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicStrategyValidationServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID CATALOG_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID AAPL_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T02:00:00Z");

    @Test
    void savesTheExactDocumentSnapshotWithLocatedWarningsAndInformation() {
        var documents = new StubDocumentPort(document(semanticDocument("RSI"), 7));
        var runs = new InMemoryValidationRunPort();
        var service = service(documents, runs, OWNER_ID);

        StrategyValidationRun run = service.validate(
                STRATEGY_ID,
                catalog(),
                new BacktestDataCoverage("data/v1", Set.of(new FeedResolution("SIP_OHLCV", "1m")), Set.of()));

        assertThat(run.id()).isEqualTo(RUN_ID);
        assertThat(run.requestedEditSequence()).isEqualTo(7);
        assertThat(run.semanticHash()).isEqualTo(documents.document.semanticHash());
        assertThat(run.elementCatalogVersionId()).isEqualTo(CATALOG_ID);
        assertThat(run.status()).isEqualTo(StrategyValidationStatus.VALID);
        assertThat(run.findings())
                .extracting(StrategyValidationFinding::severity, StrategyValidationFinding::code,
                        StrategyValidationFinding::location)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                StrategyValidationFinding.Severity.WARNING,
                                "BACKTEST_FEATURE_UNAVAILABLE",
                                "groups[0].blocks[1].elementCode"),
                        org.assertj.core.groups.Tuple.tuple(
                                StrategyValidationFinding.Severity.INFORMATION,
                                "BACKTEST_FEED_REQUIRED",
                                "groups[0].blocks[0].elementCode"),
                        org.assertj.core.groups.Tuple.tuple(
                                StrategyValidationFinding.Severity.INFORMATION,
                                "BACKTEST_FEATURE_REQUIRED",
                                "groups[0].blocks[1].elementCode"));
        assertThat(runs.saved).isEqualTo(run);
    }

    @Test
    void aLocatedAssemblyErrorBlocksValidation() {
        var documents = new StubDocumentPort(document(semanticDocument("UNKNOWN"), 2));
        var runs = new InMemoryValidationRunPort();

        StrategyValidationRun run = service(documents, runs, OWNER_ID).validate(
                STRATEGY_ID, catalog(), exactCoverage());

        assertThat(run.status()).isEqualTo(StrategyValidationStatus.INVALID);
        assertThat(run.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(StrategyValidationFinding.Severity.BLOCKING_ERROR);
            assertThat(finding.code()).isEqualTo("UNSUPPORTED_ELEMENT");
            assertThat(finding.location()).isEqualTo("groups[0].blocks[1].elementCode");
        });
    }

    @Test
    void reportsRevalidationOnlyAfterTheSavedSemanticMeaningChanges() {
        var documents = new StubDocumentPort(document(semanticDocument("RSI"), 4));
        var runs = new InMemoryValidationRunPort();
        StrategyValidationRun run = service(documents, runs, OWNER_ID).validate(
                STRATEGY_ID, catalog(), exactCoverage());
        var query = new StrategyValidationQueryService(runs, documents, new TestPrincipal(OWNER_ID));

        documents.document = documents.document.replace(
                documents.document.semanticDocument(),
                "{\"positions\":{\"condition\":{\"x\":200,\"y\":100}}}",
                documents.document.semanticSchemaVersion(),
                documents.document.presentationSchemaVersion(),
                documents.document.semanticHash(),
                StrategyDocumentJson.sha256("{\"positions\":{\"condition\":{\"x\":200,\"y\":100}}}"),
                NOW.plusSeconds(1));
        assertThat(query.getOwned(run.id()).freshness()).isEqualTo(StrategyValidationFreshness.CURRENT);

        String changed = semanticDocument("MARKET_OPEN");
        documents.document = documents.document.replace(
                changed,
                documents.document.presentationDocument(),
                documents.document.semanticSchemaVersion(),
                documents.document.presentationSchemaVersion(),
                StrategyDocumentJson.sha256(changed),
                documents.document.presentationHash(),
                NOW.plusSeconds(2));
        assertThat(query.getOwned(run.id()).freshness())
                .isEqualTo(StrategyValidationFreshness.REVALIDATION_REQUIRED);
    }

    @Test
    void doesNotPersistOrRevealValidationForAnotherOwner() {
        var documents = new StubDocumentPort(document(semanticDocument("RSI"), 1));
        var runs = new InMemoryValidationRunPort();
        var otherService = service(documents, runs, OTHER_OWNER_ID);

        assertThatThrownBy(() -> otherService.validate(STRATEGY_ID, catalog(), exactCoverage()))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessage("Strategy not found");
        assertThat(runs.saved).isNull();
    }

    private static BasicStrategyValidationCommandService service(
            StubDocumentPort documents, InMemoryValidationRunPort runs, UUID ownerId) {
        Strategy strategy = Strategy.createBasic(STRATEGY_ID, OWNER_ID, "Momentum", null, NOW);
        StrategyQueryPort strategies = (strategyId, accountId) ->
                strategyId.equals(STRATEGY_ID) && accountId.equals(OWNER_ID)
                        ? Optional.of(strategy)
                        : Optional.empty();
        return new BasicStrategyValidationCommandService(
                runs,
                strategies,
                documents,
                new TestPrincipal(ownerId),
                new FixedIdGenerator(RUN_ID),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static StrategyDocument document(String semanticDocument, long editSequence) {
        String presentation = "{\"positions\":{}}";
        return new StrategyDocument(
                STRATEGY_ID,
                semanticDocument,
                presentation,
                "basic-semantic/v1",
                "basic-presentation/v1",
                StrategyDocumentJson.sha256(semanticDocument),
                StrategyDocumentJson.sha256(presentation),
                editSequence,
                NOW.minusSeconds(60),
                NOW.minusSeconds(1));
    }

    private static String semanticDocument(String middleElement) {
        return "{\"catalogId\":\"" + CATALOG_ID + "\",\"groups\":[{"
                + "\"id\":\"buy\",\"container\":\"BUY\",\"evaluationMode\":\"INDEPENDENT\","
                + "\"allocationMode\":\"EQUAL\",\"instrumentIds\":[\"" + AAPL_ID + "\"],"
                + "\"blocks\":["
                + "{\"id\":\"trigger\",\"elementCode\":\"MARKET_OPEN\",\"parameters\":{}},"
                + "{\"id\":\"condition\",\"elementCode\":\"" + middleElement + "\",\"parameters\":{}},"
                + "{\"id\":\"order\",\"elementCode\":\"BUY_ORDER\",\"parameters\":{}}],"
                + "\"connections\":["
                + "{\"fromBlockId\":\"trigger\",\"outputPort\":\"signal\","
                + "\"toBlockId\":\"condition\",\"inputPort\":\"input\"},"
                + "{\"fromBlockId\":\"condition\",\"outputPort\":\"result\","
                + "\"toBlockId\":\"order\",\"inputPort\":\"input\"}]}]}";
    }

    private static BacktestDataCoverage exactCoverage() {
        return new BacktestDataCoverage(
                "data/v1", Set.of(new FeedResolution("SIP_OHLCV", "1m")), Set.of("RSI_14"));
    }

    private static BasicStrategyCatalog catalog() {
        return new BasicStrategyCatalog(
                new ElementCatalogVersion(
                        CATALOG_ID, "basic/v1", "schema/v1", "catalog/v1", "data/v1",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        NOW.minusSeconds(3600), null),
                List.of(
                        element("MARKET_OPEN", "{}", "{\"signal\":{\"type\":\"BOOLEAN\"}}", feedContract()),
                        element("RSI", "{\"input\":{\"type\":\"BOOLEAN\"}}",
                                "{\"result\":{\"type\":\"BOOLEAN\"}}", rsiContract()),
                        element("BUY_ORDER", "{\"input\":{\"type\":\"BOOLEAN\"}}", "{}", noDataContract())),
                List.of(new StrategyFeatureDefinition(
                        UUID.randomUUID(), CATALOG_ID, "RSI_14", "1.0.0", "1m", "{\"period\":14}",
                        "NUMBER", 14, "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")),
                List.of(new SupportedInstrument(AAPL_ID, "STOCK", "XNAS", "USD", "AAPL")));
    }

    private static StrategyElementDefinition element(String code, String inputs, String outputs, String contract) {
        return new StrategyElementDefinition(
                UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                CATALOG_ID, code, "BLOCK", "{}", inputs, outputs, contract,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    }

    private static String feedContract() {
        return "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":true,"
                + "\"feeds\":[{\"feed\":\"SIP_OHLCV\",\"resolution\":\"1m\"}],\"features\":[]}}";
    }

    private static String rsiContract() {
        return "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":true,"
                + "\"feeds\":[{\"feed\":\"SIP_OHLCV\",\"resolution\":\"1m\"}],"
                + "\"features\":[\"RSI_14\"]}}";
    }

    private static String noDataContract() {
        return "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":true,\"feeds\":[],\"features\":[]}}";
    }

    private static final class StubDocumentPort implements StrategyDocumentQueryPort {
        private StrategyDocument document;

        private StubDocumentPort(StrategyDocument document) {
            this.document = document;
        }

        @Override
        public Optional<StrategyDocument> findOwnedByStrategyId(UUID strategyId, UUID ownerAccountId) {
            return strategyId.equals(STRATEGY_ID) && ownerAccountId.equals(OWNER_ID)
                    ? Optional.of(document)
                    : Optional.empty();
        }
    }

    private static final class InMemoryValidationRunPort
            implements StrategyValidationRunCommandPort, StrategyValidationRunQueryPort {
        private StrategyValidationRun saved;

        @Override
        public void save(StrategyValidationRun run) {
            saved = run;
        }

        @Override
        public Optional<StrategyValidationRun> findOwnedById(UUID validationRunId, UUID ownerAccountId) {
            return saved != null && saved.id().equals(validationRunId) && ownerAccountId.equals(OWNER_ID)
                    ? Optional.of(saved)
                    : Optional.empty();
        }
    }
}

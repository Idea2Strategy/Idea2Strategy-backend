package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.testing.FixedIdGenerator;
import com.idea2strategy.backend.application.testing.TestPrincipal;
import com.idea2strategy.backend.domain.strategy.CompiledFlowPlan;
import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyFeatureDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyValidationRun;
import com.idea2strategy.backend.domain.strategy.StrategyValidationStatus;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicExecutionPlanCommandServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID CATALOG_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID PLAN_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID AAPL_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");

    @Test
    void compilesAndPinsTheExactValidatedBasicMeaningDeterministically() {
        var document = document(semanticDocument(), 7);
        var plans = new InMemoryPlanPort();
        var service = service(document, validRun(document), plans);

        CompiledFlowPlan first = service.compile(RUN_ID, catalog());
        CompiledFlowPlan second = service.compile(RUN_ID, catalog());

        assertThat(second).isEqualTo(first);
        assertThat(plans.saveCalls).isEqualTo(2);
        assertThat(first.id()).isEqualTo(PLAN_ID);
        assertThat(first.elementCatalogVersionId()).isEqualTo(CATALOG_ID);
        assertThat(first.semanticHash()).isEqualTo(document.semanticHash());
        assertThat(first.compilerVersion()).isEqualTo("basic-compiler:1.0.0");
        assertThat(first.requiredFeatureSetHash()).hasSize(64);
        assertThat(first.planHash()).hasSize(64);
        assertThat(first.planDocument())
                .contains("\"schemaVersion\":\"basic-compiled-plan.v1\"")
                .contains("\"catalogVersion\":\"catalog/v1\"")
                .contains("\"definitionHash\":\"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\"")
                .contains("\"featureCode\":\"RSI_14\"")
                .contains("\"evaluationPolicyVersion\":\"basic-evaluation:1.0.0\"")
                .doesNotContain("positions");
    }

    @Test
    void editHistoryDoesNotChangeAPlanWhenTheSemanticMeaningIsIdentical() {
        var firstDocument = document(semanticDocument(), 7);
        var secondDocument = document(semanticDocument(), 12);

        CompiledFlowPlan first = service(firstDocument, validRun(firstDocument), new InMemoryPlanPort())
                .compile(RUN_ID, catalog());
        CompiledFlowPlan second = service(secondDocument, validRun(secondDocument), new InMemoryPlanPort())
                .compile(RUN_ID, catalog());

        assertThat(second.planDocument()).isEqualTo(first.planDocument());
        assertThat(second.planHash()).isEqualTo(first.planHash());
    }

    @Test
    void refusesAValidationSnapshotAfterTheSemanticMeaningChanges() {
        var validated = document(semanticDocument(), 7);
        var changed = document(semanticDocument().replace("30", "31"), 8);
        var plans = new InMemoryPlanPort();

        assertThatThrownBy(() -> service(changed, validRun(validated), plans).compile(RUN_ID, catalog()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Strategy validation is stale");
        assertThat(plans.saved).isNull();
    }

    @Test
    void refusesAnInvalidValidationRun() {
        var document = document(semanticDocument(), 7);
        var invalid = new StrategyValidationRun(
                RUN_ID, STRATEGY_ID, OWNER_ID, null, 7, document.semanticHash(), CATALOG_ID,
                StrategyValidationStatus.FAILED, List.of(), NOW.minusSeconds(1), NOW);
        var plans = new InMemoryPlanPort();

        assertThatThrownBy(() -> service(document, invalid, plans).compile(RUN_ID, catalog()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Strategy validation must be VALID");
        assertThat(plans.saved).isNull();
    }

    private static BasicExecutionPlanCommandService service(
            StrategyDocument document, StrategyValidationRun run, InMemoryPlanPort plans) {
        Strategy strategy = Strategy.createBasic(STRATEGY_ID, OWNER_ID, "Momentum", null, NOW.minusSeconds(60));
        StrategyQueryPort strategies = (strategyId, accountId) -> Optional.of(strategy)
                .filter(value -> strategyId.equals(STRATEGY_ID) && accountId.equals(OWNER_ID));
        StrategyDocumentQueryPort documents = (strategyId, accountId) -> Optional.of(document)
                .filter(value -> strategyId.equals(STRATEGY_ID) && accountId.equals(OWNER_ID));
        StrategyValidationRunQueryPort validations = (runId, accountId) -> Optional.of(run)
                .filter(value -> runId.equals(RUN_ID) && accountId.equals(OWNER_ID));
        return new BasicExecutionPlanCommandService(
                plans, validations, strategies, documents, new TestPrincipal(OWNER_ID),
                new FixedIdGenerator(PLAN_ID), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static StrategyValidationRun validRun(StrategyDocument document) {
        return new StrategyValidationRun(
                RUN_ID, STRATEGY_ID, OWNER_ID, null, document.editSequence(), document.semanticHash(), CATALOG_ID,
                StrategyValidationStatus.VALID, List.of(), NOW.minusSeconds(1), NOW);
    }

    private static StrategyDocument document(String semantic, long editSequence) {
        String canonical = StrategyDocumentJson.canonicalize(semantic);
        String presentation = "{\"positions\":{}}";
        return new StrategyDocument(
                STRATEGY_ID, canonical, presentation, "basic-semantic/v1", "basic-presentation/v1",
                StrategyDocumentJson.sha256(canonical), StrategyDocumentJson.sha256(presentation), editSequence,
                NOW.minusSeconds(60), NOW.minusSeconds(1));
    }

    private static String semanticDocument() {
        return "{\"catalogId\":\"" + CATALOG_ID + "\",\"groups\":[{"
                + "\"id\":\"buy\",\"container\":\"BUY\",\"evaluationMode\":\"INDEPENDENT\","
                + "\"allocationMode\":\"EQUAL\",\"instrumentIds\":[\"" + AAPL_ID + "\"],"
                + "\"blocks\":["
                + "{\"id\":\"trigger\",\"elementCode\":\"MARKET_OPEN\",\"parameters\":{}},"
                + "{\"id\":\"condition\",\"elementCode\":\"RSI\",\"parameters\":{\"operator\":\"LT\",\"threshold\":30}},"
                + "{\"id\":\"order\",\"elementCode\":\"BUY_ORDER\",\"parameters\":{\"orderType\":\"MARKET\"}}],"
                + "\"connections\":["
                + "{\"fromBlockId\":\"trigger\",\"outputPort\":\"signal\",\"toBlockId\":\"condition\",\"inputPort\":\"input\"},"
                + "{\"fromBlockId\":\"condition\",\"outputPort\":\"result\",\"toBlockId\":\"order\",\"inputPort\":\"input\"}]}]}";
    }

    private static BasicStrategyCatalog catalog() {
        return new BasicStrategyCatalog(
                new ElementCatalogVersion(
                        CATALOG_ID, "basic/v1", "schema/v1", "catalog/v1", "data/v1",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        NOW.minusSeconds(3600), null),
                List.of(
                        element("MARKET_OPEN", "{}", "{\"signal\":{\"type\":\"BOOLEAN\"}}", noFeatureContract()),
                        element("RSI", "{\"input\":{\"type\":\"BOOLEAN\"}}",
                                "{\"result\":{\"type\":\"BOOLEAN\"}}", rsiContract()),
                        element("BUY_ORDER", "{\"input\":{\"type\":\"BOOLEAN\"}}", "{}", noFeatureContract())),
                List.of(new StrategyFeatureDefinition(
                        UUID.fromString("70000000-0000-4000-8000-000000000001"), CATALOG_ID, "RSI_14",
                        "1.0.0", "1m", "{\"period\":14}", "NUMBER", 14,
                        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")),
                List.of(new SupportedInstrument(AAPL_ID, "STOCK", "XNAS", "USD", "AAPL")));
    }

    private static StrategyElementDefinition element(String code, String inputs, String outputs, String contract) {
        return new StrategyElementDefinition(
                UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)), CATALOG_ID,
                code, "BLOCK", "{}", inputs, outputs, contract,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    }

    private static String rsiContract() {
        return "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":true,\"feeds\":[],\"features\":[\"RSI_14\"]}}";
    }

    private static String noFeatureContract() {
        return "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":true,\"feeds\":[],\"features\":[]}}";
    }

    private static final class InMemoryPlanPort implements CompiledFlowPlanCommandPort {
        private CompiledFlowPlan saved;
        private int saveCalls;

        @Override
        public CompiledFlowPlan saveOrFind(CompiledFlowPlan candidate) {
            saveCalls++;
            if (saved == null) {
                saved = candidate;
            }
            return saved;
        }
    }
}

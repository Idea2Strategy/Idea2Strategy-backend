package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.testing.TestPrincipal;
import com.idea2strategy.backend.domain.strategy.CompiledFlowPlan;
import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease;
import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyFeatureDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyValidationRun;
import com.idea2strategy.backend.domain.strategy.StrategyValidationStatus;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImmutableStrategyReleaseCommandServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID CATALOG_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID PLAN_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID RELEASE_ID = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final UUID AAPL_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID FEATURE_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID FEE_ID = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final UUID BUFFER_ID = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");

    @Test
    void releasesOneIndependentImmutableBotFromTheExactValidatedMeaning() {
        StrategyDocument document = document();
        StrategyValidationRun validation = validation(document);
        Strategy strategy = Strategy.createBasic(STRATEGY_ID, OWNER_ID, "Momentum", "Long momentum", NOW.minusSeconds(60));
        StrategyQueryPort strategies = (id, owner) -> Optional.of(strategy)
                .filter(value -> id.equals(STRATEGY_ID) && owner.equals(OWNER_ID));
        StrategyDocumentQueryPort documents = (id, owner) -> Optional.of(document)
                .filter(value -> id.equals(STRATEGY_ID) && owner.equals(OWNER_ID));
        StrategyValidationRunQueryPort validations = (id, owner) -> Optional.of(validation)
                .filter(value -> id.equals(RUN_ID) && owner.equals(OWNER_ID));
        var planPort = new InMemoryPlanPort();
        var planService = new BasicExecutionPlanCommandService(
                planPort, validations, strategies, documents, new TestPrincipal(OWNER_ID),
                () -> PLAN_ID, Clock.fixed(NOW, ZoneOffset.UTC));
        var releases = new CapturingReleasePort();
        var service = new ImmutableStrategyReleaseCommandService(
                releases, planService, validations, strategies, documents, new TestPrincipal(OWNER_ID),
                Clock.fixed(NOW, ZoneOffset.UTC));

        ImmutableStrategyRelease release = service.release(RUN_ID, catalog(), command());

        assertThat(release.botId()).isEqualTo(RELEASE_ID);
        assertThat(release.ownerAccountId()).isEqualTo(OWNER_ID);
        assertThat(release.semanticSnapshot())
                .contains("\"mode\":\"BASIC\"")
                .contains("\"partitionBudgetCapBps\":10000")
                .doesNotContain(STRATEGY_ID.toString());
        assertThat(release.presentationSnapshot()).contains("Momentum", "Long momentum");
        assertThat(release.launchConfiguration().initialCashAmount()).isEqualByComparingTo("100000.00");
        assertThat(release.partition().id()).isNotNull();
        assertThat(release.partition().flows()).singleElement().satisfies(flow -> {
            assertThat(flow.id()).isNotNull();
            assertThat(flow.compiledFlowPlanId()).isEqualTo(PLAN_ID);
            assertThat(flow.instrumentIds()).containsExactly(AAPL_ID);
            assertThat(flow.featureRequirements()).singleElement().satisfies(requirement -> {
                assertThat(requirement.instrumentId()).isEqualTo(AAPL_ID);
                assertThat(requirement.featureDefinitionId()).isEqualTo(FEATURE_ID);
            });
        });
        assertThat(releases.validationRunId).isEqualTo(RUN_ID);
        assertThat(releases.validatedEditSequence).isEqualTo(7);
        assertThat(releases.validatedSemanticHash).isEqualTo(document.semanticHash());
    }

    private static ImmutableStrategyReleaseCommand command() {
        return new ImmutableStrategyReleaseCommand(
                RELEASE_ID, new BigDecimal("100000.00"), 10_000, "broker/v1", "accounting/v1",
                "precision/v1", FEE_ID, BUFFER_ID, "{\"policy\":\"FIRST_WINS\"}");
    }

    private static StrategyDocument document() {
        String semantic = StrategyDocumentJson.canonicalize("{\"catalogId\":\"" + CATALOG_ID + "\",\"groups\":[{"
                + "\"id\":\"buy\",\"container\":\"BUY\",\"evaluationMode\":\"INDEPENDENT\","
                + "\"allocationMode\":\"EQUAL\",\"instrumentIds\":[\"" + AAPL_ID + "\"],"
                + "\"blocks\":["
                + "{\"id\":\"trigger\",\"elementCode\":\"MARKET_OPEN\",\"parameters\":{}},"
                + "{\"id\":\"condition\",\"elementCode\":\"RSI\",\"parameters\":{}},"
                + "{\"id\":\"order\",\"elementCode\":\"BUY_ORDER\",\"parameters\":{}}],"
                + "\"connections\":["
                + "{\"fromBlockId\":\"trigger\",\"outputPort\":\"signal\",\"toBlockId\":\"condition\",\"inputPort\":\"input\"},"
                + "{\"fromBlockId\":\"condition\",\"outputPort\":\"result\",\"toBlockId\":\"order\",\"inputPort\":\"input\"}]}]}");
        String presentation = StrategyDocumentJson.canonicalize("{\"positions\":{\"buy\":{\"x\":10,\"y\":20}}}");
        return new StrategyDocument(
                STRATEGY_ID, semantic, presentation, "basic-semantic/v1", "basic-presentation/v1",
                StrategyDocumentJson.sha256(semantic), StrategyDocumentJson.sha256(presentation), 7,
                NOW.minusSeconds(60), NOW.minusSeconds(1));
    }

    private static StrategyValidationRun validation(StrategyDocument document) {
        return new StrategyValidationRun(
                RUN_ID, STRATEGY_ID, OWNER_ID, null, 7, document.semanticHash(), CATALOG_ID,
                StrategyValidationStatus.VALID, List.of(), NOW.minusSeconds(2), NOW.minusSeconds(1));
    }

    private static BasicStrategyCatalog catalog() {
        return new BasicStrategyCatalog(
                new ElementCatalogVersion(
                        CATALOG_ID, "basic/v1", "schema/v1", "catalog/v1", "data/v1",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        NOW.minusSeconds(3600), null),
                List.of(
                        element("MARKET_OPEN", "{}", "{\"signal\":{\"type\":\"BOOLEAN\"}}", noFeatures()),
                        element("RSI", "{\"input\":{\"type\":\"BOOLEAN\"}}",
                                "{\"result\":{\"type\":\"BOOLEAN\"}}", rsiFeature()),
                        element("BUY_ORDER", "{\"input\":{\"type\":\"BOOLEAN\"}}", "{}", noFeatures())),
                List.of(new StrategyFeatureDefinition(
                        FEATURE_ID, CATALOG_ID, "RSI_14", "1.0.0", "1m", "{\"period\":14}",
                        "NUMBER", 14, "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")),
                List.of(new SupportedInstrument(AAPL_ID, "STOCK", "XNAS", "USD", "AAPL")));
    }

    private static StrategyElementDefinition element(
            String code, String inputs, String outputs, String executionContract) {
        return new StrategyElementDefinition(
                UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)), CATALOG_ID,
                code, "BLOCK", "{}", inputs, outputs, executionContract,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    }

    private static String rsiFeature() {
        return "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":true,\"feeds\":[],\"features\":[\"RSI_14\"]}}";
    }

    private static String noFeatures() {
        return "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":true,\"feeds\":[],\"features\":[]}}";
    }

    private static final class InMemoryPlanPort implements CompiledFlowPlanCommandPort {
        private CompiledFlowPlan saved;

        @Override
        public CompiledFlowPlan saveOrFind(CompiledFlowPlan candidate) {
            if (saved == null) {
                saved = candidate;
            }
            return saved;
        }
    }

    private static final class CapturingReleasePort implements ImmutableStrategyReleaseCommandPort {
        private UUID validationRunId;
        private long validatedEditSequence;
        private String validatedSemanticHash;

        @Override
        public ImmutableStrategyRelease saveOnce(
                ImmutableStrategyRelease release,
                UUID validationRunId,
                long validatedEditSequence,
                String validatedSemanticHash) {
            this.validationRunId = validationRunId;
            this.validatedEditSequence = validatedEditSequence;
            this.validatedSemanticHash = validatedSemanticHash;
            return release;
        }
    }

}

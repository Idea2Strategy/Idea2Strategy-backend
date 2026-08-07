package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private static final UUID DATASET_ID = UUID.fromString("a0000000-0000-4000-8000-000000000001");
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
        assertThat(releases.backtestRequest.botId()).isEqualTo(RELEASE_ID);
        assertThat(releases.backtestRequest.expectedSnapshotHash()).isEqualTo("sha256:" + release.snapshotHash());
        assertThat(releases.backtestRequest.compiledPlanChecksum()).isEqualTo("sha256:" + planPort.saved.planHash());
        assertThat(releases.backtestRequest.datasetManifestId()).isEqualTo(DATASET_ID);
        assertThat(releases.backtestRequest.assumptionsVersion()).isEqualTo("accounting/v1");
        assertThat(releases.backtestRequest.requestReason()).isEqualTo("STRATEGY_RELEASE");
        assertThat(releases.backtestRequest.metadata().idempotencyKey()).startsWith("sha256:").hasSize(71);
    }

    /**
     * Root #190: the release publishes the compiled plan the evaluation runtime loads the bot from.
     *
     * <p>The assertions that matter are the translations, because nothing else in the document could be
     * wrong without the release itself being wrong: the element catalog's runtime operations replace its
     * element codes, the block parameters fill the placeholders, the trade container becomes the order
     * side, and the feature catalog's own vocabulary becomes the consumer's — {@code rsi:1.0.0} to an
     * exact version, {@code 30m} to a normalised duration, and a fifteen-point window to the fourteen
     * observations warm-up has to supply before the live bar completes it.
     */
    @Test
    void publishesTheCompiledPlanContractTheEvaluationRuntimeLoadsTheBotFrom() {
        var release = releaseService().release(RUN_ID, catalog(), command());

        var plan = release.contractPlan();
        assertThat(plan.contractVersion()).isEqualTo("strategy-bot.v1");
        // Version 2: side, allocation and steps belong to the container (root #202).
        assertThat(plan.planSchemaVersion()).isEqualTo("basic-compiled-plan.v2");
        assertThat(plan.planChecksum()).matches("sha256:[0-9a-f]{64}");
        assertThat(plan.planDocument())
                .contains("\"snapshotHash\":\"sha256:" + release.snapshotHash() + "\"")
                .contains("\"semanticHash\":\"sha256:" + release.semanticHash() + "\"")
                .contains("\"operation\":\"LOAD_FEATURE\"")
                .contains("\"operation\":\"COMPARE\"")
                .contains("\"operation\":\"EMIT_ORDER_CANDIDATE\"")
                .contains("\"threshold\":\"30\"")
                .contains("\"side\":\"BUY\"")
                .contains("\"featureVersion\":\"1.0.0\"")
                .contains("\"resolution\":\"PT30M\"")
                .contains("\"requirementId\":\"rsi-14-pt30m\"")
                .contains("\"requiredObservations\":14")
                .contains("\"key\":\"" + release.partition().id() + "\"")
                .contains("\"instrumentCatalogVersion\":\"us-supported-universe:2026-08-01\"");
    }

    /**
     * The whole point of publishing the plan at release time: the plan and the RUN command that starts
     * the bot name one release. If they could be produced separately, a redeployed assembler could
     * publish a plan pinning a hash no command ever carries, and every start would fail verification.
     */
    @Test
    void publishesOnePlanPerReleaseWithTheSameHashItsCommandsCarry() {
        var release = releaseService().release(RUN_ID, catalog(), command());
        var again = releaseService().release(RUN_ID, catalog(), command());

        assertThat(release.contractPlan()).isEqualTo(again.contractPlan());
        assertThat(release.contractPlan().planDocument())
                .contains("\"snapshotHash\":\"sha256:" + release.snapshotHash() + "\"");
    }

    @Test
    void releaseEndpointBindsTheOwnedValidationToTheRequestedStrategyAndItsExactCatalog() {
        StrategyDocument document = document();
        StrategyValidationRun validation = validation(document);
        Strategy strategy = Strategy.createBasic(
                STRATEGY_ID, OWNER_ID, "Momentum", "Long momentum", NOW.minusSeconds(60));
        StrategyQueryPort strategies = (id, owner) -> Optional.of(strategy)
                .filter(value -> id.equals(STRATEGY_ID) && owner.equals(OWNER_ID));
        StrategyDocumentQueryPort documents = (id, owner) -> Optional.of(document)
                .filter(value -> id.equals(STRATEGY_ID) && owner.equals(OWNER_ID));
        StrategyValidationRunQueryPort validations = (id, owner) -> Optional.of(validation)
                .filter(value -> id.equals(RUN_ID) && owner.equals(OWNER_ID));
        var planService = new BasicExecutionPlanCommandService(
                new InMemoryPlanPort(), validations, strategies, documents, new TestPrincipal(OWNER_ID),
                () -> PLAN_ID, Clock.fixed(NOW, ZoneOffset.UTC));
        var releasePort = new CapturingReleasePort();
        var service = new ImmutableStrategyReleaseCommandService(
                releasePort, planService, validations, strategies, documents,
                new TestPrincipal(OWNER_ID), Clock.fixed(NOW, ZoneOffset.UTC));
        var catalogPort = new ExactCatalogPort(catalog());
        var catalogService = new BasicStrategyCatalogQueryService(
                catalogPort, Clock.fixed(NOW, ZoneOffset.UTC), ZoneOffset.UTC);

        var release = service.release(STRATEGY_ID, RUN_ID, catalogService, command());

        assertThat(release.botId()).isEqualTo(RELEASE_ID);
        assertThat(catalogPort.requestedCatalogId).isEqualTo(CATALOG_ID);
        assertThat(releasePort.backtestRequest).isNotNull();
    }

    @Test
    void releaseEndpointDoesNotSubstituteAValidationFromAnotherStrategy() {
        var service = releaseService();
        var otherStrategyId = UUID.fromString("20000000-0000-4000-8000-000000000099");
        var catalogService = new BasicStrategyCatalogQueryService(
                new ExactCatalogPort(catalog()), Clock.fixed(NOW, ZoneOffset.UTC), ZoneOffset.UTC);

        assertThatThrownBy(() -> service.release(otherStrategyId, RUN_ID, catalogService, command()))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessage("Strategy validation not found");
    }

    private ImmutableStrategyReleaseCommandService releaseService() {
        StrategyDocument document = document();
        StrategyValidationRun validation = validation(document);
        Strategy strategy = Strategy.createBasic(
                STRATEGY_ID, OWNER_ID, "Momentum", "Long momentum", NOW.minusSeconds(60));
        StrategyQueryPort strategies = (id, owner) -> Optional.of(strategy)
                .filter(value -> id.equals(STRATEGY_ID) && owner.equals(OWNER_ID));
        StrategyDocumentQueryPort documents = (id, owner) -> Optional.of(document)
                .filter(value -> id.equals(STRATEGY_ID) && owner.equals(OWNER_ID));
        StrategyValidationRunQueryPort validations = (id, owner) -> Optional.of(validation)
                .filter(value -> id.equals(RUN_ID) && owner.equals(OWNER_ID));
        var planService = new BasicExecutionPlanCommandService(
                new InMemoryPlanPort(), validations, strategies, documents, new TestPrincipal(OWNER_ID),
                () -> PLAN_ID, Clock.fixed(NOW, ZoneOffset.UTC));
        return new ImmutableStrategyReleaseCommandService(
                new CapturingReleasePort(), planService, validations, strategies, documents,
                new TestPrincipal(OWNER_ID), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ImmutableStrategyReleaseCommand command() {
        return new ImmutableStrategyReleaseCommand(
                RELEASE_ID, new BigDecimal("100000.00"), 10_000, "broker/v1", "accounting/v1",
                "precision/v1", FEE_ID, BUFFER_ID, DATASET_ID, "backtest-policy-v1",
                "{\"policy\":\"FIRST_WINS\"}");
    }

    private static StrategyDocument document() {
        String semantic = StrategyDocumentJson.canonicalize("{\"catalogId\":\"" + CATALOG_ID + "\",\"groups\":[{"
                + "\"id\":\"buy\",\"container\":\"BUY\",\"evaluationMode\":\"INDEPENDENT\","
                + "\"allocationMode\":\"EQUAL\",\"instrumentIds\":[\"" + AAPL_ID + "\"],"
                + "\"blocks\":["
                + "{\"id\":\"trigger\",\"elementCode\":\"BASIC_RSI_READ\","
                + "\"parameters\":{\"resolution\":\"30m\"}},"
                + "{\"id\":\"condition\",\"elementCode\":\"BASIC_VALUE_COMPARE\","
                + "\"parameters\":{\"operator\":\"LT\",\"threshold\":\"30\"}},"
                + "{\"id\":\"order\",\"elementCode\":\"BASIC_EQUAL_ALLOCATION_ORDER\",\"parameters\":{}}],"
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
                        element("BASIC_RSI_READ", parameters("resolution"), "{}",
                                "{\"signal\":{\"type\":\"BOOLEAN\"}}",
                                contract("LOAD_FEATURE",
                                        "{\"feature\":\"RSI_14\",\"resolution\":\"$resolution\"}",
                                        "[\"RSI_14\"]")),
                        element("BASIC_VALUE_COMPARE", parameters("operator", "threshold"),
                                "{\"input\":{\"type\":\"BOOLEAN\"}}",
                                "{\"result\":{\"type\":\"BOOLEAN\"}}",
                                contract("COMPARE",
                                        "{\"operator\":\"$operator\",\"threshold\":\"$threshold\"}", "[]")),
                        element("BASIC_EQUAL_ALLOCATION_ORDER", parameters(),
                                "{\"input\":{\"type\":\"BOOLEAN\"}}", "{}",
                                contract("EMIT_ORDER_CANDIDATE",
                                        "{\"allocation\":\"EQUAL\",\"orderType\":\"MARKET\",\"side\":\"$container\"}",
                                        "[]"))),
                List.of(new StrategyFeatureDefinition(
                        FEATURE_ID, CATALOG_ID, "RSI_14", "rsi:1.0.0", "30m", "{\"period\":14}",
                        "NUMBER", 15, "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")),
                List.of(new SupportedInstrument(AAPL_ID, "STOCK", "XNAS", "USD", "AAPL")));
    }

    private static StrategyElementDefinition element(
            String code, String parameterSchema, String inputs, String outputs, String executionContract) {
        return new StrategyElementDefinition(
                UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)), CATALOG_ID,
                code, "BLOCK", parameterSchema, inputs, outputs, executionContract,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    }

    private static String parameters(String... names) {
        if (names.length == 0) {
            return "{\"properties\":{},\"required\":[]}";
        }
        var properties = new StringBuilder();
        var required = new StringBuilder();
        for (String name : names) {
            if (!properties.isEmpty()) {
                properties.append(',');
                required.append(',');
            }
            properties.append('"').append(name).append("\":{\"type\":\"string\"}");
            required.append('"').append(name).append('"');
        }
        return "{\"properties\":{" + properties + "},\"required\":[" + required + "]}";
    }

    /**
     * An element contract in the shape the seeded catalog publishes: the backtest features it needs and
     * the runtime operation it becomes, with {@code $name} placeholders the assembler fills from the
     * block's parameters.
     */
    private static String contract(String operation, String arguments, String features) {
        return "{\"containers\":[\"BUY\"],\"runtime\":{\"operation\":\"" + operation + "\","
                + "\"arguments\":" + arguments + "},"
                + "\"backtest\":{\"supported\":true,\"feeds\":[],\"features\":" + features + "}}";
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
        private OfficialBacktestRequest backtestRequest;

        @Override
        public ImmutableStrategyRelease saveOnce(
                ImmutableStrategyRelease release,
                OfficialBacktestRequest backtestRequest,
                UUID validationRunId,
                long validatedEditSequence,
                String validatedSemanticHash) {
            this.validationRunId = validationRunId;
            this.validatedEditSequence = validatedEditSequence;
            this.validatedSemanticHash = validatedSemanticHash;
            this.backtestRequest = backtestRequest;
            return release;
        }
    }

    private static final class ExactCatalogPort implements BasicStrategyCatalogQueryPort {
        private final BasicStrategyCatalog catalog;
        private UUID requestedCatalogId;

        private ExactCatalogPort(BasicStrategyCatalog catalog) {
            this.catalog = catalog;
        }

        @Override
        public Optional<ElementCatalogVersion> findPublishedCatalog(UUID catalogId, Instant at) {
            requestedCatalogId = catalogId;
            return Optional.of(catalog.version()).filter(version -> version.id().equals(catalogId));
        }

        @Override
        public Optional<ElementCatalogVersion> findPublishedCatalog(
                String languageVersion, String schemaVersion, String catalogVersion, Instant at) {
            return Optional.empty();
        }

        @Override
        public List<StrategyElementDefinition> findElements(UUID catalogId) {
            return catalog.elements();
        }

        @Override
        public Optional<StrategyElementDefinition> findPublishedElement(
                UUID catalogId, String elementCode, Instant at) {
            return catalog.elements().stream().filter(element -> element.elementCode().equals(elementCode)).findFirst();
        }

        @Override
        public List<StrategyFeatureDefinition> findFeatures(UUID catalogId) {
            return catalog.features();
        }

        @Override
        public List<SupportedInstrument> findSupportedInstruments(Instant at, java.time.LocalDate marketDate) {
            return catalog.instruments();
        }
    }

}

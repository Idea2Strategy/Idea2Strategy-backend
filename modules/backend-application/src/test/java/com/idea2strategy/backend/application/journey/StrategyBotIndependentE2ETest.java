package com.idea2strategy.backend.application.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.botcontrol.BotExecutionPreflightService;
import com.idea2strategy.backend.application.botcontrol.BotRunCommandService;
import com.idea2strategy.backend.application.botcontrol.BotRunDispatchMode;
import com.idea2strategy.backend.application.botcontrol.BotStopCommandService;
import com.idea2strategy.backend.application.strategy.BacktestDataCoverage;
import com.idea2strategy.backend.application.strategy.BasicExecutionPlanCommandService;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalog;
import com.idea2strategy.backend.application.strategy.BasicStrategyDraftCommandPort;
import com.idea2strategy.backend.application.strategy.BasicStrategyDraftCommandService;
import com.idea2strategy.backend.application.strategy.BasicStrategyValidationCommandService;
import com.idea2strategy.backend.application.strategy.CompiledFlowPlanCommandPort;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseCommand;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseCommandService;
import com.idea2strategy.backend.application.strategy.StrategyDocumentJson;
import com.idea2strategy.backend.application.strategy.StrategyDocumentQueryPort;
import com.idea2strategy.backend.application.strategy.StrategyDraftReplaceResult;
import com.idea2strategy.backend.application.strategy.StrategyEditLeaseTokens;
import com.idea2strategy.backend.application.strategy.StrategyQueryPort;
import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalog;
import com.idea2strategy.backend.application.strategy.StrategyValidationRunCommandPort;
import com.idea2strategy.backend.application.strategy.StrategyValidationRunQueryPort;
import com.idea2strategy.backend.application.testing.FakeBacktestAdapter;
import com.idea2strategy.backend.application.testing.FakeTradingBotAdapter;
import com.idea2strategy.backend.application.testing.FixedIdGenerator;
import com.idea2strategy.backend.application.testing.RecordingDomainEventPublisher;
import com.idea2strategy.backend.application.testing.TestPrincipal;
import com.idea2strategy.backend.application.testing.TestCustomerAccessPrincipal;
import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
import com.idea2strategy.backend.domain.strategy.CompiledFlowPlan;
import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyFeatureDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyValidationRun;
import com.idea2strategy.backend.domain.strategy.StrategyValidationStatus;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StrategyBotIndependentE2ETest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000027");
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000027");
    private static final UUID VALIDATION_ID = UUID.fromString("30000000-0000-4000-8000-000000000027");
    private static final UUID PLAN_ID = UUID.fromString("40000000-0000-4000-8000-000000000027");
    private static final UUID BOT_ID = UUID.fromString("50000000-0000-4000-8000-000000000027");
    private static final UUID CATALOG_ID = UUID.fromString("60000000-0000-4000-8000-000000000027");
    private static final UUID INSTRUMENT_ID = UUID.fromString("70000000-0000-4000-8000-000000000027");
    private static final UUID FEE_POLICY_ID = UUID.fromString("80000000-0000-4000-8000-000000000027");
    private static final UUID BUFFER_POLICY_ID = UUID.fromString("90000000-0000-4000-8000-000000000027");
    private static final UUID DATASET_ID = UUID.fromString("a0000000-0000-4000-8000-000000000027");
    private static final UUID FEATURE_ID = UUID.fromString("b0000000-0000-4000-8000-000000000027");
    private static final Instant NOW = Instant.parse("2026-08-02T09:00:00Z");
    private static final String LEASE_TOKEN = "e2e-lease-token";

    @Test
    void createsValidatesReleasesRunsAndPermanentlyStopsABasicStrategyBot() {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var principal = new TestPrincipal(OWNER_ID);
        var repository = new InMemoryJourneyRepository();
        var backtest = new FakeBacktestAdapter(exactCoverage());
        var trading = new FakeTradingBotAdapter();

        var drafts = new BasicStrategyDraftCommandService(
                repository,
                repository,
                repository,
                new TestCustomerAccessPrincipal(OWNER_ID),
                new FixedIdGenerator(STRATEGY_ID),
                clock,
                new RecordingDomainEventPublisher());
        UUID strategyId = drafts.createBasic("Momentum", "Independent E2E");
        repository.activateLease(LEASE_TOKEN);
        StrategyDocument saved = drafts.saveExplicitly(
                strategyId,
                0,
                LEASE_TOKEN,
                semanticDocument(),
                "{\"positions\":{}}",
                "basic-semantic/v1",
                "basic-presentation/v1");

        var validations = new BasicStrategyValidationCommandService(
                repository,
                repository,
                repository,
                principal,
                new FixedIdGenerator(VALIDATION_ID),
                clock);
        StrategyValidationRun validation = validations.validate(strategyId, catalog());

        var plans = new BasicExecutionPlanCommandService(
                repository,
                repository.validationQuery(),
                repository,
                repository,
                principal,
                new FixedIdGenerator(PLAN_ID),
                clock);
        var releases = new ImmutableStrategyReleaseCommandService(
                trading,
                plans,
                repository.validationQuery(),
                repository,
                repository,
                observedAt -> new StrategyReleaseInputCatalog(
                        List.of(new StrategyReleaseInputCatalog.ExecutionPolicy(
                                "backtest-policy-v1", "broker/v1", "accounting/v1", "precision/v1",
                                FEE_POLICY_ID, 20, BUFFER_POLICY_ID, 1,
                                java.time.LocalDate.parse("2025-01-01"), java.time.LocalDate.parse("2025-12-31"),
                                "market-bars/1", NOW.minusSeconds(60))),
                        List.of(new StrategyReleaseInputCatalog.Dataset(
                                DATASET_ID, "ALPACA_SIP_ALL_30M", "ADJUSTED", "30m", 1,
                                java.time.LocalDate.parse("2025-01-01"), java.time.LocalDate.parse("2025-12-31"),
                                "market-bars/1", NOW.minusSeconds(30))),
                        observedAt),
                principal,
                clock);
        var release = releases.release(VALIDATION_ID, catalog(), releaseCommand());

        var preflight = new BotExecutionPreflightService(trading, principal, clock);
        var runs = new BotRunCommandService(trading, preflight, principal, clock);
        var stops = new BotStopCommandService(trading, principal, clock);
        var run = runs.issue(release.botId());
        var stop = stops.issue(release.botId(), "USER_REQUESTED");

        assertThat(strategyId).isEqualTo(STRATEGY_ID);
        assertThat(saved.editSequence()).isEqualTo(1);
        assertThat(validation.status()).isEqualTo(StrategyValidationStatus.VALID);
        /* decision.backtest.supportability: validation must not consult data coverage, because a run
           pinned by edit sequence, semantic hash and catalog version cannot hold a conclusion about
           infrastructure state. Availability is resolved at release and backtest request time. */
        assertThat(backtest.requestedStrategyIds()).isEmpty();
        assertThat(release.botId()).isEqualTo(BOT_ID);
        assertThat(trading.backtestRequest().botId()).isEqualTo(BOT_ID);
        assertThat(run.mode()).isEqualTo(BotRunDispatchMode.IMMEDIATE);
        assertThat(stop.lifecycleStatus()).isEqualTo(BotLifecycleStatus.STOPPING);
        assertThat(stop.reasonCode()).isEqualTo("USER_REQUESTED");
        assertThat(trading.runCommandCount()).isEqualTo(1);
        assertThat(trading.stopCommandCount()).isEqualTo(1);
    }

    private static ImmutableStrategyReleaseCommand releaseCommand() {
        return new ImmutableStrategyReleaseCommand(
                BOT_ID,
                new BigDecimal("100000.00"),
                10_000,
                "{\"policy\":\"FIRST_WINS\"}");
    }

    private static BacktestDataCoverage exactCoverage() {
        return new BacktestDataCoverage("data/v1", Set.of(), Set.of("RSI_14"));
    }

    private static BasicStrategyCatalog catalog() {
        return new BasicStrategyCatalog(
                new ElementCatalogVersion(
                        CATALOG_ID,
                        "basic/v1",
                        "schema/v1",
                        "catalog/v1",
                        "data/v1",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        NOW.minusSeconds(3600),
                        null),
                List.of(
                        element("BASIC_RSI_READ", "{\"properties\":{\"resolution\":{\"type\":\"string\"}},"
                                        + "\"required\":[\"resolution\"]}",
                                "{}", "{\"signal\":{\"type\":\"BOOLEAN\"}}",
                                "LOAD_FEATURE", "{\"feature\":\"RSI_14\",\"resolution\":\"$resolution\"}",
                                "[\"RSI_14\"]"),
                        element("BASIC_VALUE_COMPARE",
                                "{\"properties\":{\"operator\":{\"type\":\"string\"},"
                                        + "\"threshold\":{\"type\":\"string\"}},"
                                        + "\"required\":[\"operator\",\"threshold\"]}",
                                "{\"input\":{\"type\":\"BOOLEAN\"}}", "{\"result\":{\"type\":\"BOOLEAN\"}}",
                                "COMPARE", "{\"operator\":\"$operator\",\"threshold\":\"$threshold\"}", "[]"),
                        element("BASIC_EQUAL_ALLOCATION_ORDER", "{\"properties\":{},\"required\":[]}",
                                "{\"input\":{\"type\":\"BOOLEAN\"}}", "{}",
                                "EMIT_ORDER_CANDIDATE",
                                "{\"allocation\":\"EQUAL\",\"orderType\":\"MARKET\",\"side\":\"$container\"}",
                                "[]")),
                List.of(new StrategyFeatureDefinition(
                        FEATURE_ID, CATALOG_ID, "RSI_14", "rsi:1.0.0", "30m", "{\"period\":14}",
                        "NUMBER", 15, "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")),
                List.of(new SupportedInstrument(INSTRUMENT_ID, "STOCK", "XNAS", "USD", "AAPL")));
    }

    private static StrategyElementDefinition element(
            String code,
            String parameterSchema,
            String inputs,
            String outputs,
            String operation,
            String arguments,
            String features) {
        return new StrategyElementDefinition(
                UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8)),
                CATALOG_ID,
                code,
                "BLOCK",
                parameterSchema,
                inputs,
                outputs,
                "{\"containers\":[\"BUY\"],\"runtime\":{\"operation\":\"" + operation + "\","
                        + "\"arguments\":" + arguments + "},"
                        + "\"backtest\":{\"supported\":true,\"feeds\":[],\"features\":" + features + "}}",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    }

    private static String semanticDocument() {
        return "{\"catalogId\":\"" + CATALOG_ID + "\",\"groups\":[{"
                + "\"id\":\"buy\",\"container\":\"BUY\",\"evaluationMode\":\"INDEPENDENT\","
                + "\"allocationMode\":\"EQUAL\",\"instrumentIds\":[\"" + INSTRUMENT_ID + "\"],"
                + "\"blocks\":["
                + "{\"id\":\"trigger\",\"elementCode\":\"BASIC_RSI_READ\","
                + "\"parameters\":{\"resolution\":\"30m\"}},"
                + "{\"id\":\"condition\",\"elementCode\":\"BASIC_VALUE_COMPARE\","
                + "\"parameters\":{\"operator\":\"LT\",\"threshold\":\"30\"}},"
                + "{\"id\":\"order\",\"elementCode\":\"BASIC_EQUAL_ALLOCATION_ORDER\",\"parameters\":{}}],"
                + "\"connections\":["
                + "{\"fromBlockId\":\"trigger\",\"outputPort\":\"signal\","
                + "\"toBlockId\":\"condition\",\"inputPort\":\"input\"},"
                + "{\"fromBlockId\":\"condition\",\"outputPort\":\"result\","
                + "\"toBlockId\":\"order\",\"inputPort\":\"input\"}]}]}";
    }

    private static final class InMemoryJourneyRepository
            implements BasicStrategyDraftCommandPort,
                    StrategyQueryPort,
                    StrategyDocumentQueryPort,
                    StrategyValidationRunCommandPort,
                    CompiledFlowPlanCommandPort {
        private final Map<UUID, Strategy> strategies = new HashMap<>();
        private final Map<UUID, StrategyDocument> documents = new HashMap<>();
        private final Map<UUID, StrategyValidationRun> validations = new HashMap<>();
        private CompiledFlowPlan plan;
        private String leaseDigest;

        @Override
        public void create(Strategy strategy, StrategyDocument document) {
            strategies.put(strategy.id(), strategy);
            documents.put(strategy.id(), document);
        }

        @Override
        public StrategyDraftReplaceResult replaceDocument(
                StrategyDocument document,
                long expectedEditSequence,
                UUID accountId,
                String tokenDigest,
                Instant now) {
            StrategyDocument current = documents.get(document.strategyId());
            if (current.editSequence() != expectedEditSequence) {
                return StrategyDraftReplaceResult.STALE_EDIT_SEQUENCE;
            }
            if (!OWNER_ID.equals(accountId) || !leaseDigest.equals(tokenDigest)) {
                return StrategyDraftReplaceResult.INVALID_LEASE;
            }
            documents.put(document.strategyId(), document);
            return StrategyDraftReplaceResult.UPDATED;
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

        @Override
        public void save(StrategyValidationRun run) {
            validations.put(run.id(), run);
        }

        @Override
        public CompiledFlowPlan saveOrFind(CompiledFlowPlan candidate) {
            if (plan == null) {
                plan = candidate;
            }
            return plan;
        }

        private void activateLease(String token) {
            leaseDigest = StrategyEditLeaseTokens.sha256(token);
        }

        private StrategyValidationRunQueryPort validationQuery() {
            return (validationRunId, ownerAccountId) -> Optional.ofNullable(validations.get(validationRunId))
                    .filter(run -> run.requestedByAccountId().equals(ownerAccountId));
        }
    }
}

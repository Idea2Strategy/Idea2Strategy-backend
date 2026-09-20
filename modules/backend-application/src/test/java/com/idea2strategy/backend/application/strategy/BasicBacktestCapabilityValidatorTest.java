package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.strategy.BacktestDataCoverage.FeedResolution;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.AllocationMode;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlock;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlockConnection;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlockGroup;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.EvaluationMode;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.TradeContainer;
import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyFeatureDefinition;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicBacktestCapabilityValidatorTest {
    private static final UUID CATALOG_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID AAPL_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");

    private final BasicBacktestCapabilityValidator validator = new BasicBacktestCapabilityValidator();

    @Test
    void reportsWhatTheStrategyRequiresWithoutJudgingWhetherItIsAvailable() {
        var result = validator.validate(assembly(), catalog(supportedRsiContract()));

        assertThat(result.backtestable()).isTrue();
        assertThat(result.issues()).isEmpty();
        assertThat(result.requiredFeeds()).containsExactly(new FeedResolution("SIP_OHLCV", "1m"));
        assertThat(result.requiredFeatures()).containsExactly("RSI_14");
    }

    /* decision.backtest.supportability moves availability to release and backtest request time,
       where it is resolved against pinned artifacts. A feed declaration is therefore optional:
       adjusted bars at the evaluated resolution are a platform invariant, not a per-element claim. */
    @Test
    void acceptsAnElementThatDeclaresNoFeedBecauseAdjustedBarsAreAPlatformInvariant() {
        var result = validator.validate(
                assembly("4h", "4h"), catalog(featureOnlyContract(), featureFreeContract()));

        assertThat(result.issues()).isEmpty();
        assertThat(result.requiredFeeds()).isEmpty();
        assertThat(result.requiredFeatures()).containsExactly("RSI_14");
    }

    @Test
    void stillRejectsAFeatureTheSuppliedCatalogDoesNotDefine() {
        String unknownFeature = "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":true,"
                + "\"features\":[\"SMA_20\"]}}";

        var result = validator.validate(assembly(), catalog(unknownFeature));

        assertThat(result.backtestable()).isFalse();
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("BACKTEST_FEATURE_UNKNOWN");
            assertThat(issue.location()).isEqualTo("groups[0].blocks[1].elementCode");
                    assertThat(issue.requirements()).containsExactly("feature:SMA_20", "resolution:30m");
        });
    }

    @Test
    void rejectsAFeedDeclarationThatIsPresentButNotAnArray() {
        String malformed = "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":true,"
                + "\"feeds\":\"SIP_OHLCV\",\"features\":[]}}";

        var result = validator.validate(assembly(), catalog(malformed));

        assertThat(result.issues())
                .extracting(BasicBacktestCapabilityIssue::code)
                .containsExactly("BACKTEST_CONTRACT_INVALID");
    }

    @Test
    void oneUnsupportedBlockMakesTheWholeStrategyUnavailableWithItsReason() {
        String unsupported = "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":false,"
                + "\"reason\":\"Historical order-event sequence cannot be reproduced\","
                + "\"requirements\":[\"ORDER_EVENT_HISTORY\",\"EVENT_SEQUENCE\"]}}";

        var result = validator.validate(assembly(), catalog(unsupported));

        assertThat(result.backtestable()).isFalse();
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("BACKTEST_BLOCK_UNSUPPORTED");
            assertThat(issue.location()).isEqualTo("groups[0].blocks[1].elementCode");
            assertThat(issue.message()).isEqualTo("Historical order-event sequence cannot be reproduced");
            assertThat(issue.requirements()).containsExactly("ORDER_EVENT_HISTORY", "EVENT_SEQUENCE");
        });
    }

    @Test
    void resolvesTheProductionFeedFromEachBlocksResolutionParameter() {
        var result = validator.validate(
                assembly("4h", "4h"), catalog(dynamicRsiContract(), dynamicFeedContract()));

        assertThat(result.backtestable()).isTrue();
        assertThat(result.requiredFeeds()).containsExactly(new FeedResolution("SIP_OHLCV", "4h"));
    }

    @Test
    void rejectsUnsupportedOrMixedProductionResolutionsInsideOneFlow() {
        var mixedFlow = validator.validate(
                assembly("30m", "1h"), catalog(featureOnlyContract(), featureFreeContract()));
        var legacy = validator.validate(
                assembly("1m", "1m"), catalog(featureOnlyContract(), featureFreeContract()));

        assertThat(mixedFlow.issues()).extracting(BasicBacktestCapabilityIssue::code)
                .contains("BACKTEST_MULTIPLE_RESOLUTIONS");
        assertThat(legacy.issues()).extracting(BasicBacktestCapabilityIssue::code)
                .contains("BASIC_INVALID_RESOLUTION");
    }

    @Test
    void acceptsIndependentFlowsAtThirtyMinutesFourHoursAndOneDay() {
        var result = validator.validate(
                multiResolutionAssembly(), catalog(featureOnlyContract(), featureFreeContract()));

        assertThat(result.backtestable()).isTrue();
        assertThat(result.issues()).isEmpty();
        assertThat(result.requiredFeatures()).containsExactly("RSI_14");
    }

    private static BasicBlockAssembly assembly() {
        return assembly("30m", "30m");
    }

    private static BasicBlockAssembly assembly(String triggerResolution, String conditionResolution) {
        return new BasicBlockAssembly(CATALOG_ID, List.of(group("buy", triggerResolution, conditionResolution)));
    }

    private static BasicBlockAssembly multiResolutionAssembly() {
        return new BasicBlockAssembly(CATALOG_ID, List.of(
                group("thirty-minute", "30m", "30m"),
                group("four-hour", "4h", "4h"),
                group("daily", "1d", "1d")));
    }

    private static BasicBlockGroup group(
            String id, String triggerResolution, String conditionResolution) {
        return new BasicBlockGroup(
                id,
                TradeContainer.BUY,
                EvaluationMode.INDEPENDENT,
                AllocationMode.EQUAL,
                List.of(AAPL_ID),
                List.of(
                        new BasicBlock("trigger", "MARKET_OPEN", parameters(triggerResolution)),
                        new BasicBlock("condition", "RSI", parameters(conditionResolution)),
                        new BasicBlock("order", "BUY_ORDER", Map.of())),
                List.of(
                        new BasicBlockConnection("trigger", "signal", "condition", "input"),
                        new BasicBlockConnection("condition", "result", "order", "input")));
    }

    private static Map<String, Object> parameters(String resolution) {
        return resolution == null ? Map.of() : Map.of("resolution", resolution);
    }

    private static BasicStrategyCatalog catalog(String rsiContract) {
        return catalog(rsiContract, feedContract());
    }

    private static BasicStrategyCatalog catalog(String rsiContract, String marketOpenContract) {
        return new BasicStrategyCatalog(
                new ElementCatalogVersion(
                        CATALOG_ID,
                        "basic/v1",
                        "schema/v1",
                        "catalog/v1",
                        "data/v1",
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        Instant.parse("2026-08-01T00:00:00Z"),
                        null),
                List.of(
                        element("MARKET_OPEN", "{}", "{\"signal\":{\"type\":\"BOOLEAN\"}}", marketOpenContract),
                        element("RSI", "{\"input\":{\"type\":\"BOOLEAN\"}}", "{\"result\":{\"type\":\"BOOLEAN\"}}", rsiContract),
                        element("BUY_ORDER", "{\"input\":{\"type\":\"BOOLEAN\"}}", "{}", noDataContract())),
                java.util.stream.Stream.of("30m", "1h", "4h", "1d")
                        .map(resolution -> new StrategyFeatureDefinition(
                                UUID.nameUUIDFromBytes(("RSI_14:" + resolution).getBytes(
                                        java.nio.charset.StandardCharsets.UTF_8)),
                                CATALOG_ID,
                                "RSI_14",
                                "1.0.0",
                                resolution,
                                "{\"period\":14}",
                                "NUMBER",
                                14,
                                "c".repeat(63) + Integer.toHexString(resolution.length())))
                        .toList(),
                List.of(new SupportedInstrument(AAPL_ID, "STOCK", "XNAS", "USD", "AAPL")));
    }

    private static StrategyElementDefinition element(String code, String inputs, String outputs, String contract) {
        String parameterSchema = "BUY_ORDER".equals(code)
                ? "{\"type\":\"object\",\"properties\":{}}"
                : "{\"type\":\"object\",\"properties\":{\"resolution\":{\"type\":\"string\"}}}";
        return new StrategyElementDefinition(
                UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                CATALOG_ID,
                code,
                "BLOCK",
                parameterSchema,
                inputs,
                outputs,
                contract,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    }

    private static String feedContract() {
        return "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":true,"
                + "\"feeds\":[{\"feed\":\"SIP_OHLCV\",\"resolution\":\"1m\"}],\"features\":[]}}";
    }

    private static String dynamicFeedContract() {
        return "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":true,"
                + "\"feeds\":[{\"feed\":\"SIP_OHLCV\",\"resolution\":\"$resolution\"}],\"features\":[]}}";
    }

    private static String supportedRsiContract() {
        return "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":true,"
                + "\"feeds\":[{\"feed\":\"SIP_OHLCV\",\"resolution\":\"1m\"}],"
                + "\"features\":[\"RSI_14\"]}}";
    }

    private static String dynamicRsiContract() {
        return "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":true,"
                + "\"feeds\":[{\"feed\":\"SIP_OHLCV\",\"resolution\":\"$resolution\"}],"
                + "\"features\":[\"RSI_14\"]}}";
    }

    private static String featureOnlyContract() {
        return "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":true,\"features\":[\"RSI_14\"]}}";
    }

    private static String featureFreeContract() {
        return "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":true,\"features\":[]}}";
    }

    private static String noDataContract() {
        return "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":true,\"feeds\":[],\"features\":[]}}";
    }
}

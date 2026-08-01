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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicBacktestCapabilityValidatorTest {
    private static final UUID CATALOG_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID AAPL_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");

    private final BasicBacktestCapabilityValidator validator = new BasicBacktestCapabilityValidator();

    @Test
    void acceptsOnlyExactFeedResolutionAndFeatureCoverage() {
        var coverage = new BacktestDataCoverage(
                "data/v1",
                Set.of(new FeedResolution("SIP_OHLCV", "1m")),
                Set.of("RSI_14"));

        var result = validator.validate(assembly(), catalog(supportedRsiContract()), coverage);

        assertThat(result.backtestable()).isTrue();
        assertThat(result.issues()).isEmpty();
        assertThat(result.requiredFeeds()).containsExactly(new FeedResolution("SIP_OHLCV", "1m"));
        assertThat(result.requiredFeatures()).containsExactly("RSI_14");
    }

    @Test
    void rejectsCoarserOrDifferentDataWithoutApproximationAtEachBlock() {
        var coverage = new BacktestDataCoverage(
                "data/v1",
                Set.of(new FeedResolution("SIP_OHLCV", "5m")),
                Set.of("SMA_20"));

        var result = validator.validate(assembly(), catalog(supportedRsiContract()), coverage);

        assertThat(result.backtestable()).isFalse();
        assertThat(result.issues())
                .extracting(BasicBacktestCapabilityIssue::code, BasicBacktestCapabilityIssue::location)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("BACKTEST_FEED_UNAVAILABLE", "groups[0].blocks[0].elementCode"),
                        org.assertj.core.groups.Tuple.tuple("BACKTEST_FEED_UNAVAILABLE", "groups[0].blocks[1].elementCode"),
                        org.assertj.core.groups.Tuple.tuple("BACKTEST_FEATURE_UNAVAILABLE", "groups[0].blocks[1].elementCode"));
        assertThat(result.issues().get(0).requirements()).containsExactly("feed:SIP_OHLCV@1m");
        assertThat(result.issues().get(2).requirements()).containsExactly("feature:RSI_14");
    }

    @Test
    void oneUnsupportedBlockMakesTheWholeStrategyUnavailableWithItsReason() {
        String unsupported = "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":false,"
                + "\"reason\":\"Historical order-event sequence cannot be reproduced\","
                + "\"requirements\":[\"ORDER_EVENT_HISTORY\",\"EVENT_SEQUENCE\"]}}";

        var result = validator.validate(assembly(), catalog(unsupported), coverage());

        assertThat(result.backtestable()).isFalse();
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("BACKTEST_BLOCK_UNSUPPORTED");
            assertThat(issue.location()).isEqualTo("groups[0].blocks[1].elementCode");
            assertThat(issue.message()).isEqualTo("Historical order-event sequence cannot be reproduced");
            assertThat(issue.requirements()).containsExactly("ORDER_EVENT_HISTORY", "EVENT_SEQUENCE");
        });
    }

    @Test
    void rejectsCoverageFromAnotherCatalogDataRequirementVersion() {
        var coverage = new BacktestDataCoverage(
                "data/v2",
                Set.of(new FeedResolution("SIP_OHLCV", "1m")),
                Set.of("RSI_14"));

        var result = validator.validate(assembly(), catalog(supportedRsiContract()), coverage);

        assertThat(result.backtestable()).isFalse();
        assertThat(result.issues())
                .extracting(BasicBacktestCapabilityIssue::code, BasicBacktestCapabilityIssue::location)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "DATA_REQUIREMENT_VERSION_MISMATCH", "dataRequirementVersion"));
    }

    private static BacktestDataCoverage coverage() {
        return new BacktestDataCoverage(
                "data/v1",
                Set.of(new FeedResolution("SIP_OHLCV", "1m")),
                Set.of("RSI_14"));
    }

    private static BasicBlockAssembly assembly() {
        return new BasicBlockAssembly(CATALOG_ID, List.of(new BasicBlockGroup(
                "buy",
                TradeContainer.BUY,
                EvaluationMode.INDEPENDENT,
                AllocationMode.EQUAL,
                List.of(AAPL_ID),
                List.of(
                        new BasicBlock("trigger", "MARKET_OPEN", Map.of()),
                        new BasicBlock("condition", "RSI", Map.of()),
                        new BasicBlock("order", "BUY_ORDER", Map.of())),
                List.of(
                        new BasicBlockConnection("trigger", "signal", "condition", "input"),
                        new BasicBlockConnection("condition", "result", "order", "input")))));
    }

    private static BasicStrategyCatalog catalog(String rsiContract) {
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
                        element("MARKET_OPEN", "{}", "{\"signal\":{\"type\":\"BOOLEAN\"}}", feedContract()),
                        element("RSI", "{\"input\":{\"type\":\"BOOLEAN\"}}", "{\"result\":{\"type\":\"BOOLEAN\"}}", rsiContract),
                        element("BUY_ORDER", "{\"input\":{\"type\":\"BOOLEAN\"}}", "{}", noDataContract())),
                List.of(new StrategyFeatureDefinition(
                        UUID.randomUUID(),
                        CATALOG_ID,
                        "RSI_14",
                        "1.0.0",
                        "1m",
                        "{\"period\":14}",
                        "NUMBER",
                        14,
                        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")),
                List.of(new SupportedInstrument(AAPL_ID, "STOCK", "XNAS", "USD", "AAPL")));
    }

    private static StrategyElementDefinition element(String code, String inputs, String outputs, String contract) {
        return new StrategyElementDefinition(
                UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                CATALOG_ID,
                code,
                "BLOCK",
                "{}",
                inputs,
                outputs,
                contract,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    }

    private static String feedContract() {
        return "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":true,"
                + "\"feeds\":[{\"feed\":\"SIP_OHLCV\",\"resolution\":\"1m\"}],\"features\":[]}}";
    }

    private static String supportedRsiContract() {
        return "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":true,"
                + "\"feeds\":[{\"feed\":\"SIP_OHLCV\",\"resolution\":\"1m\"}],"
                + "\"features\":[\"RSI_14\"]}}";
    }

    private static String noDataContract() {
        return "{\"containers\":[\"BUY\"],\"backtest\":{\"supported\":true,\"feeds\":[],\"features\":[]}}";
    }
}

package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.AllocationMode;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlock;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlockConnection;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlockGroup;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.EvaluationMode;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.TradeContainer;
import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicBlockAssemblyValidatorTest {
    private static final UUID CATALOG_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID AAPL_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");

    private final BasicBlockAssemblyValidator validator = new BasicBlockAssemblyValidator();

    @Test
    void acceptsLinearBuyAndSellFlowsWithTypedInputsAndEqualIndependentAllocation() {
        var assembly = new BasicBlockAssembly(CATALOG_ID, List.of(
                group("buy", TradeContainer.BUY, "BUY_ORDER"),
                group("sell", TradeContainer.SELL, "SELL_ORDER")));

        var result = validator.validate(assembly, catalog());

        assertThat(result.valid()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void reportsDeterministicLocationsForBasicPolicyAndFlowViolations() {
        var invalid = new BasicBlockAssembly(UUID.randomUUID(), List.of(new BasicBlockGroup(
                "buy",
                TradeContainer.BUY,
                EvaluationMode.COMBINED,
                AllocationMode.WEIGHTED,
                List.of(UUID.randomUUID()),
                List.of(
                        new BasicBlock("trigger", "MARKET_OPEN", Map.of()),
                        new BasicBlock("condition", "RSI", Map.of("period", "fourteen")),
                        new BasicBlock("order", "SELL_ORDER", Map.of())),
                List.of(
                        new BasicBlockConnection("trigger", "signal", "condition", "input"),
                        new BasicBlockConnection("trigger", "signal", "order", "input")))));

        var result = validator.validate(invalid, catalog());

        assertThat(result.valid()).isFalse();
        assertThat(result.issues())
                .extracting(BasicBlockAssemblyIssue::code, BasicBlockAssemblyIssue::location)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("CATALOG_VERSION_MISMATCH", "catalogId"),
                        org.assertj.core.groups.Tuple.tuple("INDEPENDENT_EVALUATION_REQUIRED", "groups[0].evaluationMode"),
                        org.assertj.core.groups.Tuple.tuple("EQUAL_ALLOCATION_REQUIRED", "groups[0].allocationMode"),
                        org.assertj.core.groups.Tuple.tuple("UNSUPPORTED_INSTRUMENT", "groups[0].instrumentIds[0]"),
                        org.assertj.core.groups.Tuple.tuple("INVALID_PARAMETER_TYPE", "groups[0].blocks[1].parameters.period"),
                        org.assertj.core.groups.Tuple.tuple("CONTAINER_MISMATCH", "groups[0].blocks[2].elementCode"),
                        org.assertj.core.groups.Tuple.tuple("FLOW_NOT_SEQUENTIAL", "groups[0].connections"));
    }

    @Test
    void validatesRequiredDynamicParametersAndConnectedPortTypes() {
        var invalid = new BasicBlockAssembly(CATALOG_ID, List.of(new BasicBlockGroup(
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
                        new BasicBlockConnection("trigger", "signal", "condition", "period"),
                        new BasicBlockConnection("condition", "result", "order", "input")))));

        var result = validator.validate(invalid, catalog());

        assertThat(result.issues())
                .extracting(BasicBlockAssemblyIssue::code, BasicBlockAssemblyIssue::location)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("REQUIRED_PARAMETER_MISSING", "groups[0].blocks[1].parameters.period"),
                        org.assertj.core.groups.Tuple.tuple("PORT_TYPE_MISMATCH", "groups[0].connections[0]"));
    }

    @Test
    void rejectsAnOrderOnlyFlowAndAFlowWhoseTerminalOrderIsNotLast() {
        var orderOnly = new BasicBlockGroup(
                "order-only", TradeContainer.BUY, EvaluationMode.INDEPENDENT, AllocationMode.EQUAL,
                List.of(AAPL_ID), List.of(new BasicBlock("order", "BUY_ORDER", Map.of())), List.of());
        var terminalInMiddle = new BasicBlockGroup(
                "terminal-middle", TradeContainer.BUY, EvaluationMode.INDEPENDENT, AllocationMode.EQUAL,
                List.of(AAPL_ID),
                List.of(
                        new BasicBlock("trigger", "MARKET_OPEN", Map.of()),
                        new BasicBlock("order", "BUY_ORDER", Map.of()),
                        new BasicBlock("condition", "RSI", Map.of("period", 14))),
                List.of(
                        new BasicBlockConnection("trigger", "signal", "order", "input"),
                        new BasicBlockConnection("order", "signal", "condition", "input")));

        var result = validator.validate(new BasicBlockAssembly(
                CATALOG_ID, List.of(orderOnly, terminalInMiddle)), catalog());

        assertThat(result.issues()).extracting(BasicBlockAssemblyIssue::code, BasicBlockAssemblyIssue::location)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("CONDITION_REQUIRED", "groups[0].blocks"),
                        org.assertj.core.groups.Tuple.tuple("TERMINAL_ELEMENT_POSITION", "groups[1].blocks[1].elementCode"));
    }

    @Test
    void rejectsImpossiblePeriodsAndNonNumericThresholdsBeforeRelease() {
        BasicStrategyCatalog semanticCatalog = new BasicStrategyCatalog(
                catalog().version(),
                List.of(
                        element("BASIC_SMA_CROSS", "CONDITION",
                                "{\"properties\":{\"resolution\":{\"type\":\"string\"},"
                                        + "\"direction\":{\"type\":\"string\"},"
                                        + "\"shortPeriod\":{\"type\":\"string\"},"
                                        + "\"longPeriod\":{\"type\":\"string\"},"
                                        + "\"thresholdPercent\":{\"type\":\"string\"}}}",
                                "{\"passed\":{\"type\":\"boolean\"}}",
                                "{\"passed\":{\"type\":\"boolean\"}}",
                                "{\"containers\":[\"BUY\",\"SELL\"]}"),
                        element("BASIC_EQUAL_ALLOCATION_ORDER", "ACTION", "{}",
                                "{\"passed\":{\"type\":\"boolean\"}}", "{}",
                                "{\"terminal\":true,\"containers\":[\"BUY\",\"SELL\"]}")),
                List.of(), catalog().instruments());
        BasicBlockAssembly assembly = new BasicBlockAssembly(CATALOG_ID, List.of(new BasicBlockGroup(
                "buy", TradeContainer.BUY, EvaluationMode.INDEPENDENT, AllocationMode.EQUAL,
                List.of(AAPL_ID),
                List.of(
                        new BasicBlock("cross", "BASIC_SMA_CROSS", Map.of(
                                "resolution", "1m", "direction", "UP", "shortPeriod", "60",
                                "longPeriod", "20", "thresholdPercent", "not-a-number")),
                        new BasicBlock("order", "BASIC_EQUAL_ALLOCATION_ORDER", Map.of())),
                List.of(new BasicBlockConnection("cross", "passed", "order", "passed")))));

        BasicBlockAssemblyValidationResult result = validator.validate(assembly, semanticCatalog);

        assertThat(result.issues()).extracting(BasicBlockAssemblyIssue::code)
                .contains("IMPOSSIBLE_PERIOD_COMBINATION", "INVALID_PARAMETER_VALUE");
    }

    @Test
    void appliesPublishedNumericStringBoundsInsteadOfOnlyCheckingStringType() {
        BasicStrategyCatalog boundedCatalog = new BasicStrategyCatalog(
                catalog().version(),
                List.of(
                        element("TEST_LIMIT", "CONDITION",
                                "{\"type\":\"object\",\"required\":[\"amount\"],\"properties\":{"
                                        + "\"amount\":{\"type\":\"string\",\"x-numericExclusiveMinimum\":\"0\","
                                        + "\"x-numericMaximum\":\"100\"}}}",
                                "{}", "{\"passed\":{\"type\":\"boolean\"}}",
                                "{\"containers\":[\"BUY\",\"SELL\"]}"),
                        element("BASIC_EQUAL_ALLOCATION_ORDER", "ACTION", "{}",
                                "{\"passed\":{\"type\":\"boolean\"}}", "{}",
                                "{\"terminal\":true,\"containers\":[\"BUY\",\"SELL\"]}")),
                List.of(), catalog().instruments());
        var group = new BasicBlockGroup(
                "buy", TradeContainer.BUY, EvaluationMode.INDEPENDENT, AllocationMode.EQUAL,
                List.of(AAPL_ID),
                List.of(
                        new BasicBlock("limit", "TEST_LIMIT", Map.of("amount", "0")),
                        new BasicBlock("order", "BASIC_EQUAL_ALLOCATION_ORDER", Map.of())),
                List.of(new BasicBlockConnection("limit", "passed", "order", "passed")));

        var result = new BasicBlockAssemblyValidator().validate(
                new BasicBlockAssembly(CATALOG_ID, List.of(group)), boundedCatalog);

        assertThat(result.issues()).extracting(BasicBlockAssemblyIssue::code, BasicBlockAssemblyIssue::location)
                .contains(org.assertj.core.groups.Tuple.tuple(
                        "INVALID_PARAMETER_VALUE", "groups[0].blocks[0].parameters.amount"));
    }

    @Test
    void enforcesPublishedBasicCompositionLimits() {
        List<UUID> sixInstruments = java.util.stream.IntStream.range(0, 6)
                .mapToObj(index -> index == 0 ? AAPL_ID : UUID.randomUUID())
                .toList();
        List<BasicBlock> blocks = new java.util.ArrayList<>();
        for (int index = 0; index < 6; index++) {
            blocks.add(new BasicBlock("condition-" + index, "RSI", Map.of("period", 14)));
        }
        blocks.add(new BasicBlock("order", "BUY_ORDER", Map.of()));
        List<BasicBlockConnection> connections = new java.util.ArrayList<>();
        for (int index = 0; index < blocks.size() - 1; index++) {
            connections.add(new BasicBlockConnection(
                    blocks.get(index).id(), "result", blocks.get(index + 1).id(), "input"));
        }
        var oversized = new BasicBlockGroup(
                "oversized", TradeContainer.BUY, EvaluationMode.INDEPENDENT, AllocationMode.EQUAL,
                sixInstruments, blocks, connections);
        List<BasicBlockGroup> groups = new java.util.ArrayList<>();
        groups.add(oversized);
        for (int index = 0; index < 4; index++) {
            groups.add(group("extra-buy-" + index, TradeContainer.BUY, "BUY_ORDER"));
        }

        BasicBlockAssemblyValidationResult result = validator.validate(
                new BasicBlockAssembly(CATALOG_ID, groups), catalog());

        assertThat(result.issues()).extracting(BasicBlockAssemblyIssue::code, BasicBlockAssemblyIssue::location)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("TOO_MANY_BUY_CONTAINERS", "groups"),
                        org.assertj.core.groups.Tuple.tuple("TOO_MANY_INSTRUMENTS", "groups[0].instrumentIds"),
                        org.assertj.core.groups.Tuple.tuple("TOO_MANY_CONDITIONS", "groups[0].blocks"));
    }

    @Test
    void countsExpandedInstrumentFlowsByAllocationGroupInsteadOfRawGroupCount() {
        List<BasicBlockGroup> fiveInstrumentsInOnePartition = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> group("buy:" + index, "partition-buy", TradeContainer.BUY, "BUY_ORDER"))
                .toList();
        List<BasicBlockGroup> fivePartitions = java.util.stream.IntStream.range(0, 5)
                .mapToObj(index -> group("buy:" + index, "partition-" + index, TradeContainer.BUY, "BUY_ORDER"))
                .toList();

        var onePartition = new BasicBlockAssemblyValidator().validate(
                new BasicBlockAssembly(CATALOG_ID, fiveInstrumentsInOnePartition), catalog());
        var tooManyPartitions = new BasicBlockAssemblyValidator().validate(
                new BasicBlockAssembly(CATALOG_ID, fivePartitions), catalog());

        assertThat(onePartition.issues()).extracting(BasicBlockAssemblyIssue::code)
                .doesNotContain("TOO_MANY_BUY_CONTAINERS");
        assertThat(tooManyPartitions.issues()).extracting(BasicBlockAssemblyIssue::code)
                .contains("TOO_MANY_BUY_CONTAINERS");
    }

    private static BasicBlockGroup group(
            String id, String allocationGroupId, TradeContainer container, String orderElement) {
        BasicBlockGroup legacy = group(id, container, orderElement);
        return new BasicBlockGroup(
                legacy.id(), allocationGroupId, legacy.container(), legacy.evaluationMode(), legacy.allocationMode(),
                legacy.instrumentIds(), legacy.blocks(), legacy.connections());
    }

    private static BasicBlockGroup group(String id, TradeContainer container, String orderElement) {
        return new BasicBlockGroup(
                id,
                container,
                EvaluationMode.INDEPENDENT,
                AllocationMode.EQUAL,
                List.of(AAPL_ID),
                List.of(
                        new BasicBlock("trigger", "MARKET_OPEN", Map.of()),
                        new BasicBlock("condition", "RSI", Map.of("period", 14)),
                        new BasicBlock("order", orderElement, Map.of())),
                List.of(
                        new BasicBlockConnection("trigger", "signal", "condition", "input"),
                        new BasicBlockConnection("condition", "result", "order", "input")));
    }

    private static BasicStrategyCatalog catalog() {
        var version = new ElementCatalogVersion(
                CATALOG_ID,
                "basic/v1",
                "schema/v1",
                "catalog/v1",
                "data/v1",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                Instant.parse("2026-08-01T00:00:00Z"),
                null);
        return new BasicStrategyCatalog(
                version,
                List.of(
                        element("MARKET_OPEN", "TRIGGER", "{}", "{}", "{\"signal\":{\"type\":\"BOOLEAN\"}}", "{\"containers\":[\"BUY\",\"SELL\"]}"),
                        element("RSI", "CONDITION", "{\"required\":[\"period\"],\"properties\":{\"period\":{\"type\":\"integer\"}}}", "{\"input\":{\"type\":\"BOOLEAN\"}}", "{\"result\":{\"type\":\"BOOLEAN\"}}", "{\"containers\":[\"BUY\",\"SELL\"]}"),
                        element("BUY_ORDER", "ORDER", "{}", "{\"input\":{\"type\":\"BOOLEAN\"}}", "{}", "{\"containers\":[\"BUY\"]}"),
                        element("SELL_ORDER", "ORDER", "{}", "{\"input\":{\"type\":\"BOOLEAN\"}}", "{}", "{\"containers\":[\"SELL\"]}")),
                List.of(),
                List.of(new SupportedInstrument(AAPL_ID, "STOCK", "XNAS", "USD", "AAPL")));
    }

    private static StrategyElementDefinition element(
            String code, String kind, String parameters, String inputs, String outputs, String contract) {
        return new StrategyElementDefinition(
                UUID.randomUUID(),
                CATALOG_ID,
                code,
                kind,
                parameters,
                inputs,
                outputs,
                contract,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    }
}

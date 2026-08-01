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

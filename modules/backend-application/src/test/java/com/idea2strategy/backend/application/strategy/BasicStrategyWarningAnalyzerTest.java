package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.AllocationMode;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlock;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlockConnection;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlockGroup;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.EvaluationMode;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.TradeContainer;
import com.idea2strategy.backend.domain.strategy.StrategyValidationFinding;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BasicStrategyWarningAnalyzerTest {
    private static final UUID INSTRUMENT_ID = UUID.fromString("25082500-0000-4000-8000-000000000002");

    @Test
    void materializedOpposingOccurrencesEmitContradictoryCondition() {
        var assembly = assembly(TradeContainer.SELL, List.of(
                condition("lower-bound", "BASIC_DRAWDOWN_FROM_PEAK", "GTE", "10"),
                condition("upper-bound", "BASIC_DRAWDOWN_FROM_PEAK", "LT", "5")), "1");

        var warnings = new BasicStrategyWarningAnalyzer().analyze(assembly);

        assertThat(warnings).extracting(StrategyValidationFinding::code)
                .contains("CONTRADICTORY_CONDITION")
                .doesNotContain("DUPLICATE_CONDITION");
    }

    @Test
    void warnsAboutDuplicateContradictoryAndRepeatedSellExposure() {
        var assembly = assembly(TradeContainer.SELL, List.of(
                condition("floor-a", "BASIC_DRAWDOWN_FROM_PEAK", "GTE", "10"),
                condition("floor-copy", "BASIC_DRAWDOWN_FROM_PEAK", "GTE", "10"),
                condition("ceiling", "BASIC_DRAWDOWN_FROM_PEAK", "LT", "5")), "3");

        var warnings = new BasicStrategyWarningAnalyzer().analyze(assembly);

        assertThat(warnings).allMatch(finding -> finding.severity() == StrategyValidationFinding.Severity.WARNING);
        assertThat(warnings).extracting(StrategyValidationFinding::code)
                .contains("DUPLICATE_CONDITION", "CONTRADICTORY_CONDITION",
                        "REPEATED_ORDER_EXPOSURE", "SELL_REQUIRES_POSITION");
        assertThat(warnings).allSatisfy(finding -> assertThat(finding.location()).startsWith("groups[0]"));
    }

    @Test
    void warnsAboutDomainBoundaryTruthsAndARestrictiveFiveConditionFlow() {
        var assembly = assembly(TradeContainer.SELL, List.of(
                condition("always", "BASIC_DRAWDOWN_FROM_PEAK", "GTE", "0"),
                condition("never", "BASIC_DRAWDOWN_FROM_PEAK", "GT", "100"),
                condition("peak", "BASIC_PEAK_RETURN", "GTE", "20"),
                condition("return", "BASIC_POSITION_RETURN", "GTE", "5"),
                condition("holding", "BASIC_HOLDING_PERIOD", "GTE", "5")), "1");

        var warnings = new BasicStrategyWarningAnalyzer().analyze(assembly);

        assertThat(warnings).extracting(StrategyValidationFinding::code)
                .contains("CONDITION_ALWAYS_TRUE", "CONDITION_ALWAYS_FALSE", "RESTRICTIVE_COMBINATION");
    }

    private static BasicBlock condition(String id, String code, String operator, String threshold) {
        return new BasicBlock(id, code, Map.of("operator", operator, "thresholdPercent", threshold));
    }

    private static BasicBlockAssembly assembly(
            TradeContainer container, List<BasicBlock> conditions, String maxExecutions) {
        List<BasicBlock> blocks = new ArrayList<>(conditions);
        blocks.add(new BasicBlock("order", "BASIC_EQUAL_ALLOCATION_ORDER", Map.of(
                "orderPercent", "25", "maxPositionPercent", "40", "executionMode", "대기 후 재실행",
                "waitMode", "조건 재충족", "waitInterval", "1", "maxExecutions", maxExecutions)));
        List<BasicBlockConnection> connections = new ArrayList<>();
        for (int index = 0; index < blocks.size() - 1; index++) {
            connections.add(new BasicBlockConnection(
                    blocks.get(index).id(), "passed", blocks.get(index + 1).id(), "passed"));
        }
        return new BasicBlockAssembly(UUID.randomUUID(), List.of(new BasicBlockGroup(
                "group", "allocation", container, EvaluationMode.INDEPENDENT, AllocationMode.EQUAL,
                List.of(INSTRUMENT_ID), blocks, connections)));
    }
}

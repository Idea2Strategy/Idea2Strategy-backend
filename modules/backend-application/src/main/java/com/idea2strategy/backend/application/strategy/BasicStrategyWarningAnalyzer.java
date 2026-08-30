package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlock;
import com.idea2strategy.backend.domain.strategy.StrategyValidationFinding;
import com.idea2strategy.backend.domain.strategy.StrategyValidationFinding.Severity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class BasicStrategyWarningAnalyzer {
    private static final String ORDER = "BASIC_EQUAL_ALLOCATION_ORDER";

    public List<StrategyValidationFinding> analyze(BasicBlockAssembly assembly) {
        var warnings = new ArrayList<StrategyValidationFinding>();
        for (int groupIndex = 0; groupIndex < assembly.groups().size(); groupIndex++) {
            var group = assembly.groups().get(groupIndex);
            String groupPath = "groups[" + groupIndex + "]";
            List<IndexedBlock> conditions = new ArrayList<>();
            for (int blockIndex = 0; blockIndex < group.blocks().size(); blockIndex++) {
                BasicBlock block = group.blocks().get(blockIndex);
                if (ORDER.equals(block.elementCode())) {
                    BigDecimal executions = decimal(block.parameters().get("maxExecutions"));
                    if (group.container() == BasicBlockAssembly.TradeContainer.BUY
                            && executions != null && executions.compareTo(BigDecimal.ONE) > 0) {
                        add(warnings, "REPEATED_ORDER_EXPOSURE", groupPath + ".blocks[" + blockIndex
                                + "].parameters.maxExecutions", "Repeated execution can increase exposure");
                    }
                } else if (!"BASIC_SCHEDULE".equals(block.elementCode())) {
                    conditions.add(new IndexedBlock(blockIndex, block));
                }
            }

            addDuplicateWarnings(conditions, groupPath, warnings);
            addContradictionWarnings(conditions, groupPath, warnings);
            addBoundaryWarnings(conditions, groupPath, warnings);
            if (conditions.size() >= 5) {
                add(warnings, "RESTRICTIVE_COMBINATION", groupPath + ".blocks",
                        "Five chained conditions can make execution unusually rare");
            }
            if (group.container() == BasicBlockAssembly.TradeContainer.SELL) {
                add(warnings, "SELL_REQUIRES_POSITION", groupPath + ".container",
                        "A sell flow is skipped when no position is held");
            }
        }
        return List.copyOf(warnings);
    }

    public List<StrategyValidationFinding> analyzePositionCap(
            int groupIndex, BigDecimal currentPositionPercent, BigDecimal maxPositionPercent) {
        if (currentPositionPercent.compareTo(maxPositionPercent) < 0) return List.of();
        return List.of(new StrategyValidationFinding(
                Severity.WARNING,
                "POSITION_CAP_REACHED",
                "groups[" + groupIndex + "].blocks",
                "Risk-increasing orders are blocked because the instrument position cap is reached",
                List.of("currentPositionPercent:" + currentPositionPercent,
                        "maxPositionPercent:" + maxPositionPercent)));
    }

    private static void addDuplicateWarnings(
            List<IndexedBlock> conditions,
            String groupPath,
            List<StrategyValidationFinding> warnings) {
        Map<String, Integer> firstIndex = new HashMap<>();
        for (IndexedBlock condition : conditions) {
            String signature = condition.block().elementCode() + ":" + new TreeMap<>(condition.block().parameters());
            Integer duplicateOf = firstIndex.putIfAbsent(signature, condition.index());
            if (duplicateOf != null) {
                add(warnings, "DUPLICATE_CONDITION", groupPath + ".blocks[" + condition.index() + "]",
                        "Condition duplicates blocks[" + duplicateOf + "]");
            }
        }
    }

    private static void addContradictionWarnings(
            List<IndexedBlock> conditions,
            String groupPath,
            List<StrategyValidationFinding> warnings) {
        Set<String> reported = new HashSet<>();
        for (int leftIndex = 0; leftIndex < conditions.size(); leftIndex++) {
            for (int rightIndex = leftIndex + 1; rightIndex < conditions.size(); rightIndex++) {
                IndexedBlock left = conditions.get(leftIndex);
                IndexedBlock right = conditions.get(rightIndex);
                if (!sameComparableSubject(left.block(), right.block())) continue;
                Bound leftBound = bound(left.block());
                Bound rightBound = bound(right.block());
                Bound lower = leftBound != null && leftBound.lower() ? leftBound
                        : rightBound != null && rightBound.lower() ? rightBound : null;
                Bound upper = leftBound != null && !leftBound.lower() ? leftBound
                        : rightBound != null && !rightBound.lower() ? rightBound : null;
                if (lower == null || upper == null || lower.value().compareTo(upper.value()) < 0) continue;
                String key = left.index() + ":" + right.index();
                if (reported.add(key)) {
                    add(warnings, "CONTRADICTORY_CONDITION", groupPath + ".blocks[" + right.index() + "]",
                            "Numeric bounds cannot be satisfied together");
                }
            }
        }
    }

    private static boolean sameComparableSubject(BasicBlock left, BasicBlock right) {
        if (!left.elementCode().equals(right.elementCode())) return false;
        Map<String, Object> leftSubject = new TreeMap<>(left.parameters());
        Map<String, Object> rightSubject = new TreeMap<>(right.parameters());
        leftSubject.keySet().removeAll(Set.of("operator", "thresholdPercent"));
        rightSubject.keySet().removeAll(Set.of("operator", "thresholdPercent"));
        return leftSubject.equals(rightSubject);
    }

    private static Bound bound(BasicBlock block) {
        Object operator = block.parameters().get("operator");
        BigDecimal value = decimal(block.parameters().get("thresholdPercent"));
        if (!(operator instanceof String text) || value == null) return null;
        return switch (text) {
            case "GT", "GTE" -> new Bound(true, value);
            case "LT", "LTE" -> new Bound(false, value);
            default -> null;
        };
    }

    private static void addBoundaryWarnings(
            List<IndexedBlock> conditions,
            String groupPath,
            List<StrategyValidationFinding> warnings) {
        for (IndexedBlock condition : conditions) {
            if (!"BASIC_DRAWDOWN_FROM_PEAK".equals(condition.block().elementCode())) continue;
            Object operator = condition.block().parameters().get("operator");
            BigDecimal threshold = decimal(condition.block().parameters().get("thresholdPercent"));
            String location = groupPath + ".blocks[" + condition.index() + "].parameters.thresholdPercent";
            if (threshold != null && threshold.signum() == 0 && "GTE".equals(operator)) {
                add(warnings, "CONDITION_ALWAYS_TRUE", location,
                        "Drawdown is always at least zero while a position exists");
            }
            if (threshold != null && threshold.compareTo(BigDecimal.valueOf(100)) == 0
                    && "GT".equals(operator)) {
                add(warnings, "CONDITION_ALWAYS_FALSE", location,
                        "Drawdown cannot be greater than 100 percent");
            }
        }
    }

    private static BigDecimal decimal(Object value) {
        if (!(value instanceof String || value instanceof Number)) return null;
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void add(
            List<StrategyValidationFinding> warnings, String code, String location, String message) {
        warnings.add(new StrategyValidationFinding(Severity.WARNING, code, location, message, List.of()));
    }

    private record IndexedBlock(int index, BasicBlock block) {}
    private record Bound(boolean lower, BigDecimal value) {}
}

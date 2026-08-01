package com.idea2strategy.backend.application.strategy;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record BasicBlockAssembly(UUID catalogId, List<BasicBlockGroup> groups) {
    public BasicBlockAssembly {
        Objects.requireNonNull(catalogId, "catalogId");
        groups = List.copyOf(groups);
    }

    public record BasicBlockGroup(
            String id,
            TradeContainer container,
            EvaluationMode evaluationMode,
            AllocationMode allocationMode,
            List<UUID> instrumentIds,
            List<BasicBlock> blocks,
            List<BasicBlockConnection> connections) {
        public BasicBlockGroup {
            id = requireText(id, "id");
            Objects.requireNonNull(container, "container");
            Objects.requireNonNull(evaluationMode, "evaluationMode");
            Objects.requireNonNull(allocationMode, "allocationMode");
            instrumentIds = List.copyOf(instrumentIds);
            blocks = List.copyOf(blocks);
            connections = List.copyOf(connections);
        }
    }

    public record BasicBlock(String id, String elementCode, Map<String, Object> parameters) {
        public BasicBlock {
            id = requireText(id, "id");
            elementCode = requireText(elementCode, "elementCode");
            parameters = Map.copyOf(parameters);
        }
    }

    public record BasicBlockConnection(String fromBlockId, String outputPort, String toBlockId, String inputPort) {
        public BasicBlockConnection {
            fromBlockId = requireText(fromBlockId, "fromBlockId");
            outputPort = requireText(outputPort, "outputPort");
            toBlockId = requireText(toBlockId, "toBlockId");
            inputPort = requireText(inputPort, "inputPort");
        }
    }

    public enum TradeContainer {
        BUY,
        SELL
    }

    public enum EvaluationMode {
        INDEPENDENT,
        COMBINED
    }

    public enum AllocationMode {
        EQUAL,
        WEIGHTED
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

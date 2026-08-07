package com.idea2strategy.backend.application.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlock;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlockConnection;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlockGroup;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.AllocationMode;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.EvaluationMode;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class BasicBlockAssemblyValidator {
    private final ObjectMapper objectMapper;

    public BasicBlockAssemblyValidator() {
        this(new ObjectMapper());
    }

    BasicBlockAssemblyValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BasicBlockAssemblyValidationResult validate(BasicBlockAssembly assembly, BasicStrategyCatalog catalog) {
        var issues = new ArrayList<BasicBlockAssemblyIssue>();
        if (!catalog.version().id().equals(assembly.catalogId())) {
            add(issues, "CATALOG_VERSION_MISMATCH", "catalogId", "Assembly must use the supplied published catalog");
        }

        Map<String, StrategyElementDefinition> elements = new HashMap<>();
        for (var element : catalog.elements()) {
            elements.put(element.elementCode(), element);
        }
        Set<UUID> supportedInstruments = new HashSet<>();
        catalog.instruments().forEach(instrument -> supportedInstruments.add(instrument.id()));
        boolean terminalStructurePublished = catalog.elements().stream().anyMatch(this::isTerminal);

        for (int groupIndex = 0; groupIndex < assembly.groups().size(); groupIndex++) {
            validateGroup(
                    assembly.groups().get(groupIndex),
                    groupIndex,
                    elements,
                    supportedInstruments,
                    terminalStructurePublished,
                    issues);
        }
        return new BasicBlockAssemblyValidationResult(issues);
    }

    private void validateGroup(
            BasicBlockGroup group,
            int groupIndex,
            Map<String, StrategyElementDefinition> elements,
            Set<UUID> supportedInstruments,
            boolean terminalStructurePublished,
            List<BasicBlockAssemblyIssue> issues) {
        String groupPath = "groups[" + groupIndex + "]";
        if (group.evaluationMode() != EvaluationMode.INDEPENDENT) {
            add(issues, "INDEPENDENT_EVALUATION_REQUIRED", groupPath + ".evaluationMode",
                    "Basic evaluates each instrument independently");
        }
        if (group.allocationMode() != AllocationMode.EQUAL) {
            add(issues, "EQUAL_ALLOCATION_REQUIRED", groupPath + ".allocationMode",
                    "Basic permits equal allocation only");
        }
        for (int instrumentIndex = 0; instrumentIndex < group.instrumentIds().size(); instrumentIndex++) {
            if (!supportedInstruments.contains(group.instrumentIds().get(instrumentIndex))) {
                add(issues, "UNSUPPORTED_INSTRUMENT", groupPath + ".instrumentIds[" + instrumentIndex + "]",
                        "Instrument is not supported by the catalog");
            }
        }

        Map<String, StrategyElementDefinition> blockDefinitions = new HashMap<>();
        for (int blockIndex = 0; blockIndex < group.blocks().size(); blockIndex++) {
            BasicBlock block = group.blocks().get(blockIndex);
            String blockPath = groupPath + ".blocks[" + blockIndex + "]";
            StrategyElementDefinition definition = elements.get(block.elementCode());
            if (definition == null) {
                add(issues, "UNSUPPORTED_ELEMENT", blockPath + ".elementCode",
                        "Element is not present in the supplied catalog");
                continue;
            }
            blockDefinitions.put(block.id(), definition);
            validateParameters(block, definition, blockPath, issues);
            validateKnownSemantics(block, blockPath, issues);
            validateContainer(group, definition, blockPath, issues);
        }

        if (terminalStructurePublished) {
            validateTerminalStructure(group, groupIndex, blockDefinitions, issues);
        }

        if (!isSequential(group)) {
            add(issues, "FLOW_NOT_SEQUENTIAL", groupPath + ".connections",
                    "Basic connections must form exactly one ordered linear flow");
            return;
        }
        validatePortTypes(group, groupIndex, blockDefinitions, issues);
    }

    private void validateTerminalStructure(
            BasicBlockGroup group,
            int groupIndex,
            Map<String, StrategyElementDefinition> definitions,
            List<BasicBlockAssemblyIssue> issues) {
        String groupPath = "groups[" + groupIndex + "]";
        if (group.blocks().size() < 2) {
            add(issues, "CONDITION_REQUIRED", groupPath + ".blocks",
                    "A Basic flow needs a trigger or condition before its order action");
        }
        int terminalCount = 0;
        int triggerCount = 0;
        for (int index = 0; index < group.blocks().size(); index++) {
            StrategyElementDefinition definition = definitions.get(group.blocks().get(index).id());
            if (definition != null && "TRIGGER".equals(definition.elementKind())) {
                triggerCount++;
            }
            if (definition == null || !isTerminal(definition)) {
                continue;
            }
            terminalCount++;
            if (index != group.blocks().size() - 1) {
                add(issues, "TERMINAL_ELEMENT_POSITION", groupPath + ".blocks[" + index + "].elementCode",
                        "An order action must be the final block in its flow");
            }
        }
        if (terminalCount == 0) {
            add(issues, "TERMINAL_ELEMENT_REQUIRED", groupPath + ".blocks",
                    "A Basic flow must end with one order action");
        } else if (terminalCount > 1) {
            add(issues, "MULTIPLE_TERMINAL_ELEMENTS", groupPath + ".blocks",
                    "A Basic flow may contain exactly one order action");
        }
        if (triggerCount > 1) {
            add(issues, "MULTIPLE_TRIGGER_ELEMENTS", groupPath + ".blocks",
                    "A Basic flow may contain only one schedule trigger");
        }
    }

    private static void validateKnownSemantics(
            BasicBlock block, String blockPath, List<BasicBlockAssemblyIssue> issues) {
        if ("BASIC_SMA_CROSS".equals(block.elementCode())) {
            BigDecimal shortPeriod = decimal(block.parameters().get("shortPeriod"));
            BigDecimal longPeriod = decimal(block.parameters().get("longPeriod"));
            if (shortPeriod != null && longPeriod != null
                    && shortPeriod.compareTo(longPeriod) >= 0) {
                add(issues, "IMPOSSIBLE_PERIOD_COMBINATION", blockPath + ".parameters",
                        "The short moving average period must be smaller than the long period");
            }
        }
        for (String name : List.of(
                "threshold", "thresholdPercent", "orderPercent", "waitInterval",
                "maxExecutions", "interval")) {
            if (!block.parameters().containsKey(name)) {
                continue;
            }
            BigDecimal value = decimal(block.parameters().get(name));
            String location = blockPath + ".parameters." + name;
            if (value == null) {
                add(issues, "INVALID_PARAMETER_VALUE", location,
                        "Parameter must be a decimal number");
                continue;
            }
            if ((name.equals("thresholdPercent") || name.equals("waitInterval"))
                    && value.signum() < 0) {
                add(issues, "INVALID_PARAMETER_VALUE", location,
                        "Parameter must not be negative");
            }
            if ((name.equals("orderPercent") || name.equals("maxExecutions")
                    || name.equals("interval")) && value.signum() <= 0) {
                add(issues, "INVALID_PARAMETER_VALUE", location,
                        "Parameter must be greater than zero");
            }
            if (name.equals("orderPercent") && value.compareTo(BigDecimal.valueOf(100)) > 0) {
                add(issues, "INVALID_PARAMETER_VALUE", location,
                        "Order percent must not exceed 100");
            }
        }
    }

    private static BigDecimal decimal(Object value) {
        String text = value instanceof Number number
                ? number.toString()
                : value instanceof String string ? string : null;
        if (text == null || text.isBlank()) return null;
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean isTerminal(StrategyElementDefinition definition) {
        JsonNode contract = parse(definition.executionContract(), "elementCode", new ArrayList<>());
        if (contract != null && "EMIT_ORDER_CANDIDATE".equals(
                contract.path("runtime").path("operation").asText())) {
            return true;
        }
        if ("ORDER".equals(definition.elementKind()) || "ACTION".equals(definition.elementKind())) {
            return contract == null || !contract.has("terminal") || contract.path("terminal").asBoolean();
        }
        return contract != null && contract.path("terminal").asBoolean(false);
    }

    private void validateParameters(
            BasicBlock block,
            StrategyElementDefinition definition,
            String blockPath,
            List<BasicBlockAssemblyIssue> issues) {
        JsonNode schema = parse(definition.parameterSchema(), blockPath + ".parameters", issues);
        if (schema == null) {
            return;
        }
        for (JsonNode required : schema.path("required")) {
            String name = required.asText();
            if (!block.parameters().containsKey(name)) {
                add(issues, "REQUIRED_PARAMETER_MISSING", blockPath + ".parameters." + name,
                        "Required parameter is missing");
            }
        }
        schema.path("properties").properties().forEach(property -> {
            if (block.parameters().containsKey(property.getKey())) {
                Object value = block.parameters().get(property.getKey());
                String location = blockPath + ".parameters." + property.getKey();
                if (!matchesType(value, property.getValue().path("type").asText())) {
                    add(issues, "INVALID_PARAMETER_TYPE", location,
                            "Parameter does not match the catalog type");
                } else if (!matchesValueConstraints(value, property.getValue())) {
                    add(issues, "INVALID_PARAMETER_VALUE", location,
                            "Parameter is outside the values published by the catalog");
                }
            }
        });
        block.parameters().keySet().stream()
                .filter(name -> !schema.path("properties").has(name))
                .sorted()
                .forEach(name -> add(
                        issues,
                        "UNDECLARED_PARAMETER",
                        blockPath + ".parameters." + name,
                        "Parameter is not declared by the published catalog"));
    }

    private boolean matchesValueConstraints(Object value, JsonNode schema) {
        JsonNode actual = objectMapper.valueToTree(value);
        if (schema.has("minLength") && actual.isTextual()
                && actual.textValue().length() < schema.path("minLength").asInt()) {
            return false;
        }
        if (schema.path("enum").isArray()) {
            for (JsonNode permitted : schema.path("enum")) {
                if (permitted.equals(actual)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private void validateContainer(
            BasicBlockGroup group,
            StrategyElementDefinition definition,
            String blockPath,
            List<BasicBlockAssemblyIssue> issues) {
        JsonNode contract = parse(definition.executionContract(), blockPath + ".elementCode", issues);
        if (contract == null || !contract.has("containers")) {
            return;
        }
        boolean supported = false;
        for (JsonNode container : contract.path("containers")) {
            supported |= group.container().name().equals(container.asText());
        }
        if (!supported) {
            add(issues, "CONTAINER_MISMATCH", blockPath + ".elementCode",
                    "Element cannot execute in the " + group.container() + " container");
        }
    }

    private static boolean isSequential(BasicBlockGroup group) {
        if (group.blocks().isEmpty() || group.connections().size() != group.blocks().size() - 1) {
            return false;
        }
        for (int index = 0; index < group.connections().size(); index++) {
            BasicBlockConnection connection = group.connections().get(index);
            if (!connection.fromBlockId().equals(group.blocks().get(index).id())
                    || !connection.toBlockId().equals(group.blocks().get(index + 1).id())) {
                return false;
            }
        }
        return true;
    }

    private void validatePortTypes(
            BasicBlockGroup group,
            int groupIndex,
            Map<String, StrategyElementDefinition> blockDefinitions,
            List<BasicBlockAssemblyIssue> issues) {
        for (int connectionIndex = 0; connectionIndex < group.connections().size(); connectionIndex++) {
            BasicBlockConnection connection = group.connections().get(connectionIndex);
            StrategyElementDefinition from = blockDefinitions.get(connection.fromBlockId());
            StrategyElementDefinition to = blockDefinitions.get(connection.toBlockId());
            if (from == null || to == null) {
                continue;
            }
            String location = "groups[" + groupIndex + "].connections[" + connectionIndex + "]";
            JsonNode outputs = parse(from.outputPortSchema(), location, issues);
            JsonNode inputs = parse(to.inputPortSchema(), location, issues);
            if (outputs == null || inputs == null) {
                continue;
            }
            String outputType = outputs.path(connection.outputPort()).path("type").asText();
            String inputType = inputs.path(connection.inputPort()).path("type").asText();
            if (outputType.isBlank() || !outputType.equals(inputType)) {
                add(issues, "PORT_TYPE_MISMATCH", location, "Connected catalog port types must match");
            }
        }
    }

    private JsonNode parse(String json, String location, List<BasicBlockAssemblyIssue> issues) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            add(issues, "INVALID_CATALOG_SCHEMA", location, "Catalog schema is not valid JSON");
            return null;
        }
    }

    private static boolean matchesType(Object value, String type) {
        return switch (type) {
            case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            case "", "null" -> true;
            default -> false;
        };
    }

    private static void add(
            List<BasicBlockAssemblyIssue> issues, String code, String location, String message) {
        issues.add(new BasicBlockAssemblyIssue(code, location, message));
    }
}

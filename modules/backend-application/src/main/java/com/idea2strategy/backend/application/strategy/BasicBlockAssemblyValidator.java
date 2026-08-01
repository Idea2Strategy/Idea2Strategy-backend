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

        for (int groupIndex = 0; groupIndex < assembly.groups().size(); groupIndex++) {
            validateGroup(
                    assembly.groups().get(groupIndex),
                    groupIndex,
                    elements,
                    supportedInstruments,
                    issues);
        }
        return new BasicBlockAssemblyValidationResult(issues);
    }

    private void validateGroup(
            BasicBlockGroup group,
            int groupIndex,
            Map<String, StrategyElementDefinition> elements,
            Set<UUID> supportedInstruments,
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
            validateContainer(group, definition, blockPath, issues);
        }

        if (!isSequential(group)) {
            add(issues, "FLOW_NOT_SEQUENTIAL", groupPath + ".connections",
                    "Basic connections must form exactly one ordered linear flow");
            return;
        }
        validatePortTypes(group, groupIndex, blockDefinitions, issues);
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
            if (block.parameters().containsKey(property.getKey())
                    && !matchesType(block.parameters().get(property.getKey()), property.getValue().path("type").asText())) {
                add(issues, "INVALID_PARAMETER_TYPE", blockPath + ".parameters." + property.getKey(),
                        "Parameter does not match the catalog type");
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

package com.idea2strategy.backend.application.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlockGroup;
import com.idea2strategy.backend.domain.strategy.CompiledFlowPlan;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyFeatureDefinition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class BasicExecutionPlanCompiler {
    static final String COMPILER_VERSION = "basic-compiler:1.0.0";
    private static final String PLAN_SCHEMA_VERSION = "basic-compiled-plan.v1";
    private static final String EVALUATION_POLICY_VERSION = "basic-evaluation:1.0.0";
    private static final String ALLOCATION_POLICY_VERSION = "basic-allocation:1.0.0";
    private static final String ORDER_CANDIDATE_POLICY_VERSION = "basic-order-candidate:1.0.0";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final BasicBlockAssemblyValidator assemblyValidator = new BasicBlockAssemblyValidator();

    CompiledFlowPlan compile(
            UUID planId,
            StrategyDocument document,
            BasicStrategyCatalog catalog,
            Instant createdAt) {
        BasicBlockAssembly assembly = parseAssembly(document.semanticDocument());
        var result = assemblyValidator.validate(assembly, catalog);
        if (!result.valid()) {
            throw new IllegalStateException("Validated strategy no longer matches its catalog");
        }

        Map<String, StrategyElementDefinition> elements = new HashMap<>();
        catalog.elements().forEach(element -> elements.put(element.elementCode(), element));
        List<StrategyFeatureDefinition> requiredFeatures = requiredFeatures(assembly, catalog, elements);
        String featureDocument = featureDocument(requiredFeatures);
        String featureHash = StrategyDocumentJson.sha256(featureDocument);
        String planDocument = planDocument(document, catalog, assembly, elements, requiredFeatures, featureHash);

        return new CompiledFlowPlan(
                planId,
                catalog.version().id(),
                document.semanticHash(),
                COMPILER_VERSION,
                featureHash,
                planDocument,
                StrategyDocumentJson.sha256(planDocument),
                createdAt);
    }

    private BasicBlockAssembly parseAssembly(String semanticDocument) {
        try {
            JsonNode root = objectMapper.readTree(semanticDocument);
            UUID catalogId = UUID.fromString(root.path("catalogId").asText());
            List<BasicBlockGroup> groups = objectMapper.convertValue(
                    root.path("groups"), new TypeReference<List<BasicBlockGroup>>() {});
            return new BasicBlockAssembly(catalogId, groups);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("Validated strategy document is not compilable", exception);
        }
    }

    private List<StrategyFeatureDefinition> requiredFeatures(
            BasicBlockAssembly assembly,
            BasicStrategyCatalog catalog,
            Map<String, StrategyElementDefinition> elements) {
        Map<FeatureKey, StrategyFeatureDefinition> definitions = new HashMap<>();
        catalog.features().forEach(feature -> definitions.put(
                new FeatureKey(feature.featureCode(), feature.resolution()), feature));
        Set<FeatureKey> requiredKeys = new LinkedHashSet<>();
        for (var group : assembly.groups()) {
            for (var block : group.blocks()) {
                try {
                    JsonNode contract = objectMapper.readTree(elements.get(block.elementCode()).executionContract());
                    for (JsonNode feature : contract.path("backtest").path("features")) {
                        requiredKeys.add(resolveFeatureKey(
                                definitions, feature.asText(), block.parameters().get("resolution"), block.elementCode()));
                    }
                } catch (JsonProcessingException exception) {
                    throw new IllegalStateException("Validated element contract is not compilable", exception);
                }
            }
        }
        var required = new ArrayList<StrategyFeatureDefinition>();
        requiredKeys.stream()
                .sorted(Comparator.comparing(FeatureKey::featureCode).thenComparing(FeatureKey::resolution))
                .forEach(key -> {
            StrategyFeatureDefinition definition = definitions.get(key);
            if (definition == null) {
                throw new IllegalStateException(
                        "Validated feature is missing from its catalog: "
                                + key.featureCode() + "@" + key.resolution());
            }
            required.add(definition);
        });
        return List.copyOf(required);
    }

    private FeatureKey resolveFeatureKey(
            Map<FeatureKey, StrategyFeatureDefinition> definitions,
            String featureCode,
            Object configured,
            String elementCode) {
        if (configured instanceof String resolution && !resolution.isBlank()) {
            return new FeatureKey(featureCode, resolution);
        }
        List<FeatureKey> matches = definitions.keySet().stream()
                .filter(key -> key.featureCode().equals(featureCode))
                .toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        throw new IllegalStateException(
                "Feature block " + elementCode + " must select exactly one resolution");
    }

    private record FeatureKey(String featureCode, String resolution) {}

    private String featureDocument(List<StrategyFeatureDefinition> features) {
        ArrayNode root = objectMapper.createArrayNode();
        for (var feature : features) {
            ObjectNode node = root.addObject();
            node.put("calculatorVersion", feature.calculatorVersion());
            node.put("definitionHash", feature.definitionHash());
            node.put("featureCode", feature.featureCode());
            node.set("normalizedParameters", parseJson(feature.normalizedParameters()));
            node.put("outputValueType", feature.outputValueType());
            node.put("requiredHistoryPoints", feature.requiredHistoryPoints());
            node.put("resolution", feature.resolution());
        }
        return canonical(root);
    }

    private String planDocument(
            StrategyDocument document,
            BasicStrategyCatalog catalog,
            BasicBlockAssembly assembly,
            Map<String, StrategyElementDefinition> elements,
            List<StrategyFeatureDefinition> features,
            String featureHash) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", PLAN_SCHEMA_VERSION);
        root.put("compilerVersion", COMPILER_VERSION);
        root.put("semanticSchemaVersion", document.semanticSchemaVersion());
        root.put("semanticHash", document.semanticHash());

        ObjectNode catalogNode = root.putObject("elementCatalog");
        catalogNode.put("id", catalog.version().id().toString());
        catalogNode.put("languageVersion", catalog.version().languageVersion());
        catalogNode.put("schemaVersion", catalog.version().schemaVersion());
        catalogNode.put("catalogVersion", catalog.version().catalogVersion());
        catalogNode.put("dataRequirementVersion", catalog.version().dataRequirementVersion());
        catalogNode.put("definitionHash", catalog.version().definitionHash());

        ObjectNode policies = root.putObject("policyVersions");
        policies.put("evaluationPolicyVersion", EVALUATION_POLICY_VERSION);
        policies.put("allocationPolicyVersion", ALLOCATION_POLICY_VERSION);
        policies.put("orderCandidatePolicyVersion", ORDER_CANDIDATE_POLICY_VERSION);
        root.put("requiredFeatureSetHash", featureHash);
        root.set("requiredFeatures", parseJson(featureDocument(features)));

        ArrayNode flows = root.putArray("flows");
        List<BasicBlockGroup> orderedGroups = assembly.groups().stream()
                .sorted(Comparator
                        .comparing((BasicBlockGroup group) -> group.instrumentIds().stream()
                                .map(UUID::toString).min(String::compareTo).orElse(""))
                        .thenComparing(BasicBlockGroup::id))
                .toList();
        for (var group : orderedGroups) {
            ObjectNode flow = flows.addObject();
            flow.put("key", group.id());
            flow.put("allocationGroupId", group.allocationGroupId());
            flow.put("container", group.container().name());
            flow.put("evaluationMode", group.evaluationMode().name());
            flow.put("allocationMode", group.allocationMode().name());
            ArrayNode instruments = flow.putArray("instrumentIds");
            group.instrumentIds().stream().map(UUID::toString).sorted().forEach(instruments::add);
            ArrayNode steps = flow.putArray("steps");
            for (int index = 0; index < group.blocks().size(); index++) {
                var block = group.blocks().get(index);
                var definition = elements.get(block.elementCode());
                ObjectNode step = steps.addObject();
                step.put("sequence", index + 1);
                step.put("key", block.id());
                step.put("elementCode", block.elementCode());
                step.put("elementDefinitionHash", definition.definitionHash());
                step.put("executionTemplateHash", StrategyDocumentJson.sha256(
                        StrategyDocumentJson.canonicalize(definition.executionContract())));
                step.set("parameters", objectMapper.valueToTree(block.parameters()));
            }
            ArrayNode connections = flow.putArray("connections");
            group.connections().stream()
                    .sorted(Comparator.comparing(BasicBlockAssembly.BasicBlockConnection::fromBlockId)
                            .thenComparing(BasicBlockAssembly.BasicBlockConnection::outputPort)
                            .thenComparing(BasicBlockAssembly.BasicBlockConnection::toBlockId)
                            .thenComparing(BasicBlockAssembly.BasicBlockConnection::inputPort))
                    .forEach(connection -> {
                        ObjectNode edge = connections.addObject();
                        edge.put("fromBlockId", connection.fromBlockId());
                        edge.put("outputPort", connection.outputPort());
                        edge.put("toBlockId", connection.toBlockId());
                        edge.put("inputPort", connection.inputPort());
                    });
        }
        return canonical(root);
    }

    private String canonical(JsonNode node) {
        try {
            return StrategyDocumentJson.canonicalize(objectMapper.writeValueAsString(node));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Compiled plan could not be serialized", exception);
        }
    }

    private JsonNode parseJson(String document) {
        try {
            return objectMapper.readTree(document);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Pinned catalog document is not valid JSON", exception);
        }
    }
}

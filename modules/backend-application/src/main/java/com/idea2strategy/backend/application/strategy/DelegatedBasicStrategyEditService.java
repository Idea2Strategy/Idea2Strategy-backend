package com.idea2strategy.backend.application.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idea2strategy.backend.application.strategy.BasicBlockAssembly.BasicBlockGroup;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import com.idea2strategy.backend.domain.strategy.StrategyElementDefinition;
import com.idea2strategy.backend.domain.strategy.StrategyMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DelegatedBasicStrategyEditService {
    private static final String ADD_GROUP = "ADD_GROUP";
    private static final String ADD_BLOCK = "ADD_BLOCK";
    private static final String REMOVE_BLOCK = "REMOVE_BLOCK";
    private static final String CONNECT_BLOCKS = "CONNECT_BLOCKS";
    private static final String SET_VALUE = "SET_VALUE";

    private final StrategyQueryPort strategyPort;
    private final StrategyDocumentQueryPort documentPort;
    private final DelegatedStrategyAuthorizationPort authorizationPort;
    private final DelegatedBasicEditCommandPort commandPort;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BasicBlockAssemblyValidator validator = new BasicBlockAssemblyValidator();
    private final BasicNaturalLanguageTranslator translator = new BasicNaturalLanguageTranslator();

    public DelegatedBasicStrategyEditService(
            StrategyQueryPort strategyPort,
            StrategyDocumentQueryPort documentPort,
            DelegatedStrategyAuthorizationPort authorizationPort,
            DelegatedBasicEditCommandPort commandPort,
            Clock clock) {
        this.strategyPort = Objects.requireNonNull(strategyPort, "strategyPort");
        this.documentPort = Objects.requireNonNull(documentPort, "documentPort");
        this.authorizationPort = Objects.requireNonNull(authorizationPort, "authorizationPort");
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DelegatedBasicEditPreview preview(
            DelegatedStrategyEditor editor,
            UUID strategyId,
            long expectedEditSequence,
            BasicStrategyCatalog catalog,
            List<DelegatedBasicEditOperation> operations) {
        return prepare(editor, strategyId, expectedEditSequence, catalog, operations, DelegatedStrategyScope.STRATEGY_EDIT);
    }

    public BasicBlockAssemblyValidationResult validate(
            DelegatedStrategyEditor editor,
            UUID strategyId,
            BasicStrategyCatalog catalog) {
        authorizationPort.requireAuthorized(
                editor, strategyId, DelegatedStrategyScope.STRATEGY_VALIDATE, clock.instant());
        StrategyDocument document = requireDocument(editor, strategyId);
        return validator.validate(parseAssembly(document.semanticDocument()), catalog);
    }

    public StrategyDocument apply(
            DelegatedStrategyEditor editor,
            UUID strategyId,
            long expectedEditSequence,
            BasicStrategyCatalog catalog,
            List<DelegatedBasicEditOperation> operations,
            String reviewedPreviewHash) {
        var preview = prepare(
                editor, strategyId, expectedEditSequence, catalog, operations,
                DelegatedStrategyScope.STRATEGY_EDIT);
        if (!preview.previewHash().equals(reviewedPreviewHash)) {
            throw new DelegatedBasicEditPreviewMismatchException(
                    "Reviewed preview does not match the requested edit");
        }
        if (!preview.valid()) {
            throw new DelegatedBasicEditRejectedException(
                    "Only a valid official Basic strategy preview can be applied");
        }
        StrategyDocument current = requireDocument(editor, strategyId);
        var replacement = current.replace(
                preview.proposedSemanticDocument(),
                current.presentationDocument(),
                BasicStrategyDraftCommandService.SEMANTIC_SCHEMA_VERSION,
                current.presentationSchemaVersion(),
                preview.previewHash(),
                current.presentationHash(),
                clock.instant());
        return switch (commandPort.replace(replacement, expectedEditSequence, editor, clock.instant())) {
            case UPDATED -> replacement;
            case STALE_EDIT_SEQUENCE -> throw new StrategyDraftConflictException();
            case UNAUTHORIZED -> throw new DelegatedStrategyScopeDeniedException(
                    "Delegated authorization is not active for strategy editing");
        };
    }

    private DelegatedBasicEditPreview prepare(
            DelegatedStrategyEditor editor,
            UUID strategyId,
            long expectedEditSequence,
            BasicStrategyCatalog catalog,
            List<DelegatedBasicEditOperation> operations,
            DelegatedStrategyScope scope) {
        Objects.requireNonNull(editor, "editor");
        Objects.requireNonNull(catalog, "catalog");
        operations = List.copyOf(Objects.requireNonNull(operations, "operations"));
        if (operations.isEmpty()) {
            throw new DelegatedBasicEditRejectedException("At least one reviewed Basic edit is required");
        }
        authorizationPort.requireAuthorized(editor, strategyId, scope, clock.instant());
        StrategyDocument current = requireDocument(editor, strategyId);
        if (current.editSequence() != expectedEditSequence) {
            throw new StrategyDraftConflictException();
        }
        ObjectNode root = parseRoot(current.semanticDocument());
        // A strategy starts as {"groups":[],"mode":"BASIC"} with no catalogId, and every proposed
        // document has to parse as an official assembly, which requires one. Without this a
        // delegated tool could create a container and still never produce a readable document —
        // the first edit would fail on a field no delegated operation can set. The value is not
        // invented: it is the catalog this very edit is being validated against.
        if (!root.hasNonNull("catalogId") || root.path("catalogId").asText().isBlank()) {
            root.put("catalogId", catalog.version().id().toString());
        }
        Map<String, StrategyElementDefinition> definitions = catalog.elements().stream()
                .collect(Collectors.toMap(StrategyElementDefinition::elementCode, Function.identity()));
        var changes = new ArrayList<String>();
        for (DelegatedBasicEditOperation operation : operations) {
            applyOperation(root, definitions, operation, changes);
        }
        String proposed = canonical(root);
        BasicBlockAssembly assembly = parseAssembly(proposed);
        var validation = validator.validate(assembly, catalog);
        var review = translator.translate(assembly, catalog);
        return new DelegatedBasicEditPreview(
                expectedEditSequence,
                current.semanticHash(),
                StrategyDocumentJson.sha256(proposed),
                proposed,
                changes,
                validation,
                review);
    }

    private StrategyDocument requireDocument(DelegatedStrategyEditor editor, UUID strategyId) {
        var strategy = strategyPort.findOwnedById(strategyId, editor.accountId())
                .orElseThrow(() -> new NoSuchElementException("Strategy not found"));
        if (strategy.mode() != StrategyMode.BASIC) {
            throw new DelegatedBasicEditRejectedException("Delegated editing permits BASIC strategies only");
        }
        return documentPort.findOwnedByStrategyId(strategyId, editor.accountId())
                .orElseThrow(() -> new NoSuchElementException("Strategy document not found"));
    }

    private void applyOperation(
            ObjectNode root,
            Map<String, StrategyElementDefinition> definitions,
            DelegatedBasicEditOperation operation,
            List<String> changes) {
        switch (operation.action()) {
            case ADD_GROUP -> addGroup(root, operation.arguments(), changes);
            case ADD_BLOCK -> addBlock(root, definitions, operation.arguments(), changes);
            case REMOVE_BLOCK -> removeBlock(root, operation.arguments(), changes);
            case CONNECT_BLOCKS -> connectBlocks(root, operation.arguments(), changes);
            case SET_VALUE -> setValue(root, definitions, operation.arguments(), changes);
            default -> throw new DelegatedBasicEditRejectedException(
                    "Delegated operation is not allowed: " + operation.action());
        }
    }

    /**
     * Creates a trade container so a delegated tool can start from an empty strategy.
     *
     * <p>A container is more than a bag of blocks: it carries the side, how its blocks combine, how
     * capital is split, and which instruments it trades. Those are the parts of a strategy a
     * customer is most likely to have an opinion about, so the arguments are all explicit and the
     * one-container-per-side rule is enforced here rather than left to validation — a second BUY
     * container has no defined meaning, and refusing it at the operation says so where the tool can
     * still react.
     */
    private void addGroup(ObjectNode root, Map<String, Object> arguments, List<String> changes) {
        String groupId = text(arguments, "groupId");
        ArrayNode groups = array(root, "groups");
        if (find(groups, "id", groupId) != null) {
            throw new DelegatedBasicEditRejectedException("Block group id already exists: " + groupId);
        }
        String container = enumeration(arguments, "container", BasicBlockAssembly.TradeContainer.class);
        for (JsonNode existing : groups) {
            if (container.equals(existing.path("container").asText())) {
                throw new DelegatedBasicEditRejectedException(
                        "A strategy holds one container per side; " + container + " already exists");
            }
        }

        ObjectNode group = objectMapper.createObjectNode();
        group.put("id", groupId);
        group.put("container", container);
        group.put(
                "evaluationMode",
                enumeration(arguments, "evaluationMode", BasicBlockAssembly.EvaluationMode.class));
        group.put(
                "allocationMode",
                enumeration(arguments, "allocationMode", BasicBlockAssembly.AllocationMode.class));
        group.set("instrumentIds", instrumentIds(arguments));
        group.set("blocks", objectMapper.createArrayNode());
        group.set("connections", objectMapper.createArrayNode());
        groups.add(group);
        changes.add("ADD_GROUP " + groupId + " " + container);
    }

    private ArrayNode instrumentIds(Map<String, Object> arguments) {
        Object value = arguments.get("instrumentIds");
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            throw new DelegatedBasicEditRejectedException(
                    "A container must name the instruments it trades: instrumentIds");
        }
        ArrayNode instruments = objectMapper.createArrayNode();
        for (Object instrument : values) {
            if (!(instrument instanceof String text)) {
                throw new DelegatedBasicEditRejectedException("instrumentIds must be identifiers");
            }
            try {
                instruments.add(UUID.fromString(text).toString());
            } catch (IllegalArgumentException exception) {
                throw new DelegatedBasicEditRejectedException("Instrument id is not a UUID: " + text);
            }
        }
        return instruments;
    }

    private static <E extends Enum<E>> String enumeration(
            Map<String, Object> arguments, String key, Class<E> type) {
        String value = text(arguments, key);
        try {
            return Enum.valueOf(type, value).name();
        } catch (IllegalArgumentException exception) {
            throw new DelegatedBasicEditRejectedException(
                    key + " must be one of " + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }

    private void addBlock(
            ObjectNode root,
            Map<String, StrategyElementDefinition> definitions,
            Map<String, Object> arguments,
            List<String> changes) {
        String groupId = text(arguments, "groupId");
        String blockId = text(arguments, "blockId");
        String elementCode = text(arguments, "elementCode");
        StrategyElementDefinition definition = definitions.get(elementCode);
        if (definition == null) {
            throw new DelegatedBasicEditRejectedException(
                    "Element is not present in the official catalog: " + elementCode);
        }
        ObjectNode group = group(root, groupId);
        ArrayNode blocks = array(group, "blocks");
        if (find(blocks, "id", blockId) != null) {
            throw new DelegatedBasicEditRejectedException("Block id already exists: " + blockId);
        }
        ObjectNode block = objectMapper.createObjectNode();
        block.put("id", blockId);
        block.put("elementCode", elementCode);
        Object parameters = arguments.getOrDefault("parameters", Map.of());
        if (!(parameters instanceof Map<?, ?>)) {
            throw new DelegatedBasicEditRejectedException("Block parameters must be an object");
        }
        block.set("parameters", objectMapper.valueToTree(parameters));
        validateDeclaredParameters(block.path("parameters"), definition);
        Object indexValue = arguments.get("index");
        if (indexValue == null) {
            blocks.add(block);
        } else if (indexValue instanceof Number number
                && number.intValue() >= 0
                && number.intValue() <= blocks.size()) {
            blocks.insert(number.intValue(), block);
        } else {
            throw new DelegatedBasicEditRejectedException("Block index is outside the group");
        }
        changes.add("ADD_BLOCK " + groupId + "/" + blockId + " " + elementCode);
    }

    private void removeBlock(ObjectNode root, Map<String, Object> arguments, List<String> changes) {
        String groupId = text(arguments, "groupId");
        String blockId = text(arguments, "blockId");
        ObjectNode group = group(root, groupId);
        ArrayNode blocks = array(group, "blocks");
        int blockIndex = indexOf(blocks, "id", blockId);
        if (blockIndex < 0) {
            throw new DelegatedBasicEditRejectedException("Block not found: " + blockId);
        }
        blocks.remove(blockIndex);
        ArrayNode connections = array(group, "connections");
        for (int index = connections.size() - 1; index >= 0; index--) {
            JsonNode connection = connections.get(index);
            if (blockId.equals(connection.path("fromBlockId").asText())
                    || blockId.equals(connection.path("toBlockId").asText())) {
                connections.remove(index);
            }
        }
        changes.add("REMOVE_BLOCK " + groupId + "/" + blockId);
    }

    private void connectBlocks(ObjectNode root, Map<String, Object> arguments, List<String> changes) {
        String groupId = text(arguments, "groupId");
        ObjectNode group = group(root, groupId);
        ObjectNode connection = objectMapper.createObjectNode();
        connection.put("fromBlockId", text(arguments, "fromBlockId"));
        connection.put("outputPort", text(arguments, "outputPort"));
        connection.put("toBlockId", text(arguments, "toBlockId"));
        connection.put("inputPort", text(arguments, "inputPort"));
        array(group, "connections").add(connection);
        changes.add("CONNECT_BLOCKS " + groupId + "/"
                + connection.path("fromBlockId").asText() + "->" + connection.path("toBlockId").asText());
    }

    private void setValue(
            ObjectNode root,
            Map<String, StrategyElementDefinition> definitions,
            Map<String, Object> arguments,
            List<String> changes) {
        String groupId = text(arguments, "groupId");
        String blockId = text(arguments, "blockId");
        String parameter = text(arguments, "parameter");
        ObjectNode block = find(array(group(root, groupId), "blocks"), "id", blockId);
        if (block == null) {
            throw new DelegatedBasicEditRejectedException("Block not found: " + blockId);
        }
        StrategyElementDefinition definition = definitions.get(block.path("elementCode").asText());
        requireDeclaredParameter(definition, parameter);
        ObjectNode parameters = object(block, "parameters");
        parameters.set(parameter, objectMapper.valueToTree(arguments.get("value")));
        changes.add("SET_VALUE " + groupId + "/" + blockId + "/" + parameter);
    }

    private void validateDeclaredParameters(JsonNode parameters, StrategyElementDefinition definition) {
        parameters.fieldNames().forEachRemaining(name -> requireDeclaredParameter(definition, name));
    }

    private void requireDeclaredParameter(StrategyElementDefinition definition, String parameter) {
        if (definition == null || !parseRoot(definition.parameterSchema()).path("properties").has(parameter)) {
            throw new DelegatedBasicEditRejectedException(
                    "Parameter is not declared by the official catalog: " + parameter);
        }
    }

    private ObjectNode group(ObjectNode root, String groupId) {
        ObjectNode group = find(array(root, "groups"), "id", groupId);
        if (group == null) {
            throw new DelegatedBasicEditRejectedException("Block group not found: " + groupId);
        }
        return group;
    }

    private static String text(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new DelegatedBasicEditRejectedException("Operation argument is required: " + key);
        }
        return text;
    }

    private static ArrayNode array(ObjectNode object, String field) {
        JsonNode value = object.get(field);
        if (!(value instanceof ArrayNode array)) {
            throw new DelegatedBasicEditRejectedException("Basic document field must be an array: " + field);
        }
        return array;
    }

    private static ObjectNode object(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (!(value instanceof ObjectNode object)) {
            throw new DelegatedBasicEditRejectedException("Basic document field must be an object: " + field);
        }
        return object;
    }

    private static ObjectNode find(ArrayNode values, String field, String expected) {
        int index = indexOf(values, field, expected);
        return index < 0 ? null : (ObjectNode) values.get(index);
    }

    private static int indexOf(ArrayNode values, String field, String expected) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).isObject() && expected.equals(values.get(index).path(field).asText())) {
                return index;
            }
        }
        return -1;
    }

    private ObjectNode parseRoot(String json) {
        try {
            JsonNode value = objectMapper.readTree(json);
            if (value instanceof ObjectNode object) {
                return object;
            }
            throw new DelegatedBasicEditRejectedException("Basic strategy document must be a JSON object");
        } catch (JsonProcessingException exception) {
            throw new DelegatedBasicEditRejectedException("Basic strategy document is not valid JSON");
        }
    }

    private BasicBlockAssembly parseAssembly(String semanticDocument) {
        ObjectNode root = parseRoot(semanticDocument);
        try {
            UUID catalogId = UUID.fromString(root.path("catalogId").asText());
            List<BasicBlockGroup> groups = objectMapper.convertValue(
                    root.path("groups"), new TypeReference<List<BasicBlockGroup>>() {});
            return new BasicBlockAssembly(catalogId, groups);
        } catch (IllegalArgumentException exception) {
            throw new DelegatedBasicEditRejectedException(
                    "Basic strategy document does not match the official schema");
        }
    }

    private String canonical(JsonNode node) {
        try {
            return StrategyDocumentJson.canonicalize(objectMapper.writeValueAsString(node));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Basic strategy preview could not be serialized", exception);
        }
    }
}

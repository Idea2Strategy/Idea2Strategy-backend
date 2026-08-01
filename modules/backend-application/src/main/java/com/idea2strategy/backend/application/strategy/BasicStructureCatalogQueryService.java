package com.idea2strategy.backend.application.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class BasicStructureCatalogQueryService {
    private static final Set<String> MATERIAL_FIELDS = Set.of(
            "period",
            "threshold",
            "value",
            "ratio",
            "amount",
            "quantity",
            "budget",
            "budgetCapBps",
            "priority",
            "timeframe",
            "duration");

    private final BasicStructureCatalogQueryPort queryPort;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public BasicStructureCatalogQueryService(BasicStructureCatalogQueryPort queryPort, Clock clock) {
        this(queryPort, clock, new ObjectMapper());
    }

    BasicStructureCatalogQueryService(
            BasicStructureCatalogQueryPort queryPort,
            Clock clock,
            ObjectMapper objectMapper) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public List<BasicStructureVersion> getPublished(BasicStrategyCatalog catalog) {
        Set<String> officialElements = new HashSet<>();
        catalog.elements().forEach(element -> officialElements.add(element.elementCode()));
        var versions = new ArrayList<BasicStructureVersion>();
        Set<String> identities = new HashSet<>();
        for (var candidate : queryPort.findActivePublishedByCatalogId(catalog.version().id(), clock.instant())) {
            if (!candidate.elementCatalogVersionId().equals(catalog.version().id())) {
                throw invalid(candidate, "catalog version does not match");
            }
            if (candidate.publishedAt().isAfter(clock.instant())) {
                throw invalid(candidate, "version is not published yet");
            }
            String identity = candidate.code() + "@" + candidate.version();
            if (!identities.add(identity)) {
                throw invalid(candidate, "duplicate code and version");
            }
            versions.add(toVersion(candidate, officialElements));
        }

        boolean hasBuy = versions.stream().anyMatch(version -> version.kind() == BasicStructureKind.BUY_TEMPLATE);
        boolean hasSell = versions.stream().anyMatch(version -> version.kind() == BasicStructureKind.SELL_TEMPLATE);
        if (!hasBuy || !hasSell) {
            throw new InvalidBasicStructureDefinitionException(
                    "catalog", "both BUY and SELL templates must be published");
        }
        versions.sort(Comparator.comparing(BasicStructureVersion::kind)
                .thenComparing(BasicStructureVersion::code)
                .thenComparing(BasicStructureVersion::version));
        return List.copyOf(versions);
    }

    private BasicStructureVersion toVersion(
            BasicStructureCandidate candidate,
            Set<String> officialElements) {
        try {
            String canonical = StrategyDocumentJson.canonicalize(candidate.flowDocument());
            if (!StrategyDocumentJson.sha256(canonical).equals(candidate.contentHash())) {
                throw invalid(candidate, "content hash does not match the canonical document");
            }
            JsonNode root = objectMapper.readTree(canonical);
            if (!"BASIC".equals(root.path("mode").asText())) {
                throw invalid(candidate, "structure must use BASIC mode");
            }
            BasicStructureKind kind;
            try {
                kind = BasicStructureKind.valueOf(root.path("kind").asText());
            } catch (IllegalArgumentException exception) {
                throw invalid(candidate, "kind is not a supported Basic structure kind");
            }
            validateContainer(candidate, root, kind);
            validateUnsetValues(candidate, root);
            validateElements(candidate, root, officialElements);
            Map<String, String> names = readI18n(candidate, candidate.nameDocument(), "name");
            Map<String, String> descriptions = readI18n(candidate, candidate.descriptionDocument(), "description");
            return new BasicStructureVersion(
                    candidate.id(),
                    candidate.packageId(),
                    candidate.code(),
                    kind,
                    candidate.version(),
                    candidate.elementCatalogVersionId(),
                    names,
                    descriptions,
                    canonical,
                    candidate.contentHash(),
                    candidate.publishedAt());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw invalid(candidate, "documents must be valid JSON");
        }
    }

    private static void validateContainer(
            BasicStructureCandidate candidate,
            JsonNode root,
            BasicStructureKind kind) {
        String container = root.path("container").asText();
        if (kind == BasicStructureKind.BUY_TEMPLATE && !"BUY".equals(container)) {
            throw invalid(candidate, "BUY template must use the BUY container");
        }
        if (kind == BasicStructureKind.SELL_TEMPLATE && !"SELL".equals(container)) {
            throw invalid(candidate, "SELL template must use the SELL container");
        }
        if (kind == BasicStructureKind.PACKAGE && !Set.of("BUY", "SELL").contains(container)) {
            throw invalid(candidate, "package must declare a BUY or SELL container");
        }
    }

    private static void validateUnsetValues(BasicStructureCandidate candidate, JsonNode root) {
        JsonNode instruments = root.path("instrumentIds");
        if (!instruments.isArray() || !instruments.isEmpty()) {
            throw invalid(candidate, "material values must be unset, including instruments");
        }
        if (containsMaterialValue(root)) {
            throw invalid(candidate, "material values must be unset");
        }
        for (JsonNode block : root.path("blocks")) {
            if (containsAssignedValue(block.path("parameters"))) {
                throw invalid(candidate, "material values must be unset");
            }
        }
    }

    private static boolean containsMaterialValue(JsonNode node) {
        if (!node.isContainerNode()) {
            return false;
        }
        if (node.isObject()) {
            for (var property : node.properties()) {
                if (MATERIAL_FIELDS.contains(property.getKey())
                        && !property.getValue().isNull()
                        && !(property.getValue().isContainerNode() && property.getValue().isEmpty())) {
                    return true;
                }
                if (containsMaterialValue(property.getValue())) {
                    return true;
                }
            }
        } else {
            for (JsonNode child : node) {
                if (containsMaterialValue(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsAssignedValue(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isArray()) {
            return !node.isEmpty();
        }
        if (node.isObject()) {
            for (JsonNode value : node) {
                if (containsAssignedValue(value)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static void validateElements(
            BasicStructureCandidate candidate,
            JsonNode root,
            Set<String> officialElements) {
        JsonNode blocks = root.path("blocks");
        if (!blocks.isArray() || blocks.isEmpty()) {
            throw invalid(candidate, "structure must contain at least one block");
        }
        for (JsonNode block : blocks) {
            String code = block.path("elementCode").asText();
            if (!officialElements.contains(code)) {
                throw invalid(candidate, "structure contains unofficial element " + code);
            }
            if (!block.path("parameters").isObject()) {
                throw invalid(candidate, "every block must declare unset parameters");
            }
        }
    }

    private Map<String, String> readI18n(
            BasicStructureCandidate candidate,
            String document,
            String field) throws JsonProcessingException {
        Map<String, String> values = objectMapper.readValue(document, new TypeReference<Map<String, String>>() {});
        if (!hasText(values.get("ko"))
                || !hasText(values.get("en"))
                || values.entrySet().stream().anyMatch(entry -> !hasText(entry.getKey()) || !hasText(entry.getValue()))) {
            throw invalid(candidate, field + " must provide ko and en text");
        }
        return Map.copyOf(values);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static InvalidBasicStructureDefinitionException invalid(
            BasicStructureCandidate candidate,
            String reason) {
        return new InvalidBasicStructureDefinitionException(candidate.code(), reason);
    }
}

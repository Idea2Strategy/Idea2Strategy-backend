package com.idea2strategy.backend.application.competition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.domain.competition.ScoringAdjustmentDefinition;
import com.idea2strategy.backend.domain.competition.ScoringAdjustmentUnit;
import com.idea2strategy.backend.domain.competition.ScoringComponent;
import com.idea2strategy.backend.domain.competition.ScoringDirection;
import com.idea2strategy.backend.domain.competition.ScoringMetric;
import com.idea2strategy.backend.domain.competition.ScoringTemplateKind;
import com.idea2strategy.backend.domain.competition.ScoringTemplateSelection;
import com.idea2strategy.backend.domain.competition.ScoringTemplateVersion;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ScoringTemplateCatalogService {
    private static final Set<String> ROOT_FIELDS =
            Set.of("kind", "calculationRulesVersion", "components", "adjustments");
    private static final Set<String> COMPONENT_FIELDS =
            Set.of("metric", "direction", "coefficient");
    private static final Set<String> ADJUSTMENT_FIELDS =
            Set.of("code", "unit", "minimum", "maximum", "scale");

    private final ScoringTemplateCatalogQueryPort queryPort;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public ScoringTemplateCatalogService(
            ScoringTemplateCatalogQueryPort queryPort, Clock clock, ObjectMapper objectMapper) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public List<ScoringTemplateVersion> listSelectable() {
        return queryPort.findSelectableAt(clock.instant()).stream()
                .map(this::parse)
                .sorted(Comparator.comparing(ScoringTemplateVersion::templateCode)
                        .thenComparing(ScoringTemplateVersion::version))
                .toList();
    }

    public ScoringTemplateSelection select(UUID templateId, Map<String, BigDecimal> adjustments) {
        Objects.requireNonNull(templateId, "templateId");
        var record = queryPort
                .findSelectableById(templateId, clock.instant())
                .orElseThrow(() -> new ScoringTemplateNotFoundException(templateId));
        var template = parse(record);
        try {
            return new ScoringTemplateSelection(template, adjustments);
        } catch (IllegalArgumentException exception) {
            throw new InvalidScoringAdjustmentException(exception.getMessage(), exception);
        }
    }

    private ScoringTemplateVersion parse(ScoringTemplateCatalogRecord record) {
        try {
            JsonNode root = objectMapper.readTree(record.rulesDocument());
            requireObject(root, "rules document");
            requireExactFields(root, ROOT_FIELDS, "rules document");
            ScoringTemplateKind kind = enumValue(root, "kind", ScoringTemplateKind.class);
            String calculationRulesVersion = text(root, "calculationRulesVersion");
            List<ScoringComponent> components = components(root.path("components"));
            List<ScoringAdjustmentDefinition> adjustments = adjustments(root.path("adjustments"));
            return new ScoringTemplateVersion(
                    record.id(),
                    record.templateCode(),
                    record.version(),
                    kind,
                    calculationRulesVersion,
                    components,
                    adjustments,
                    record.rulesHash(),
                    record.publishedAt(),
                    record.retiredAt());
        } catch (InvalidScoringTemplateException exception) {
            throw exception;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new InvalidScoringTemplateException(
                    "Invalid scoring template " + record.templateCode() + ": " + exception.getMessage(), exception);
        }
    }

    private List<ScoringComponent> components(JsonNode node) {
        requireArray(node, "components");
        var components = new ArrayList<ScoringComponent>();
        for (JsonNode component : node) {
            requireObject(component, "component");
            requireExactFields(component, COMPONENT_FIELDS, "component");
            components.add(new ScoringComponent(
                    enumValue(component, "metric", ScoringMetric.class),
                    enumValue(component, "direction", ScoringDirection.class),
                    decimal(component, "coefficient")));
        }
        return List.copyOf(components);
    }

    private List<ScoringAdjustmentDefinition> adjustments(JsonNode node) {
        requireArray(node, "adjustments");
        var adjustments = new ArrayList<ScoringAdjustmentDefinition>();
        for (JsonNode adjustment : node) {
            requireObject(adjustment, "adjustment");
            requireExactFields(adjustment, ADJUSTMENT_FIELDS, "adjustment");
            adjustments.add(new ScoringAdjustmentDefinition(
                    text(adjustment, "code"),
                    enumValue(adjustment, "unit", ScoringAdjustmentUnit.class),
                    decimal(adjustment, "minimum"),
                    decimal(adjustment, "maximum"),
                    integer(adjustment, "scale")));
        }
        return List.copyOf(adjustments);
    }

    private static void requireExactFields(JsonNode node, Set<String> allowed, String location) {
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        for (String field : actual) {
            if (!allowed.contains(field)) {
                throw new InvalidScoringTemplateException("Unsupported " + location + " field: " + field);
            }
        }
        for (String field : allowed) {
            if (!actual.contains(field)) {
                throw new InvalidScoringTemplateException("Missing " + location + " field: " + field);
            }
        }
    }

    private static void requireObject(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            throw new InvalidScoringTemplateException(field + " must be an object");
        }
    }

    private static void requireArray(JsonNode node, String field) {
        if (node == null || !node.isArray()) {
            throw new InvalidScoringTemplateException(field + " must be an array");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new InvalidScoringTemplateException(field + " must be non-blank text");
        }
        return value.textValue();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new InvalidScoringTemplateException(field + " must be a number");
        }
        return value.decimalValue();
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new InvalidScoringTemplateException(field + " must be an integer");
        }
        return value.intValue();
    }

    private static <T extends Enum<T>> T enumValue(JsonNode node, String field, Class<T> type) {
        String value = text(node, field);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidScoringTemplateException("Unsupported " + field + ": " + value, exception);
        }
    }
}

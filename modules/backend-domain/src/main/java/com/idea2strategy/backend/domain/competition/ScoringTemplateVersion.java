package com.idea2strategy.backend.domain.competition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public record ScoringTemplateVersion(
        UUID id,
        String templateCode,
        String version,
        ScoringTemplateKind kind,
        String calculationRulesVersion,
        List<ScoringComponent> components,
        List<ScoringAdjustmentDefinition> adjustmentDefinitions,
        String rulesHash,
        Instant publishedAt,
        Instant retiredAt) {
    public ScoringTemplateVersion {
        Objects.requireNonNull(id, "id");
        templateCode = requireText(templateCode, "templateCode");
        version = requireText(version, "version");
        Objects.requireNonNull(kind, "kind");
        calculationRulesVersion = requireText(calculationRulesVersion, "calculationRulesVersion");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
        adjustmentDefinitions = List.copyOf(Objects.requireNonNull(adjustmentDefinitions, "adjustmentDefinitions"));
        rulesHash = requireText(rulesHash, "rulesHash");
        Objects.requireNonNull(publishedAt, "publishedAt");
        if (retiredAt != null && retiredAt.isBefore(publishedAt)) {
            throw new IllegalArgumentException("retiredAt must not precede publishedAt");
        }
        validateComponents(kind, components);
        requireUnique(components.stream().map(ScoringComponent::metric).toList(), "metric");
        requireUnique(adjustmentDefinitions.stream().map(ScoringAdjustmentDefinition::code).toList(), "adjustment");
    }

    public Map<String, BigDecimal> validateAdjustments(Map<String, BigDecimal> adjustments) {
        Objects.requireNonNull(adjustments, "adjustments");
        var definitions = adjustmentDefinitions.stream()
                .collect(Collectors.toUnmodifiableMap(ScoringAdjustmentDefinition::code, Function.identity()));
        for (String code : adjustments.keySet()) {
            if (!definitions.containsKey(code)) {
                throw new IllegalArgumentException("unknown scoring adjustment: " + code);
            }
        }
        for (var definition : adjustmentDefinitions) {
            BigDecimal value = adjustments.get(definition.code());
            if (value == null) {
                throw new IllegalArgumentException("missing scoring adjustment: " + definition.code());
            }
            definition.validate(value);
        }
        return Map.copyOf(adjustments);
    }

    private static void validateComponents(ScoringTemplateKind kind, List<ScoringComponent> components) {
        if (kind == ScoringTemplateKind.SINGLE) {
            if (components.size() != 1) {
                throw new IllegalArgumentException("single scoring template must contain exactly one component");
            }
            if (components.getFirst().coefficient().compareTo(BigDecimal.ONE) != 0) {
                throw new IllegalArgumentException("single scoring template coefficient must equal one");
            }
            return;
        }
        if (components.size() < 2) {
            throw new IllegalArgumentException("composite scoring template must contain at least two components");
        }
        BigDecimal total = components.stream()
                .map(ScoringComponent::coefficient)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("composite scoring template coefficients must sum to one");
        }
    }

    private static void requireUnique(List<?> values, String field) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException("duplicate scoring " + field);
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

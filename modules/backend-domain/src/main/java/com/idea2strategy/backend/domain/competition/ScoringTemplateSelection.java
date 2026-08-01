package com.idea2strategy.backend.domain.competition;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

public record ScoringTemplateSelection(
        ScoringTemplateVersion template, Map<String, BigDecimal> adjustments) {
    public ScoringTemplateSelection {
        Objects.requireNonNull(template, "template");
        adjustments = template.validateAdjustments(adjustments);
    }
}

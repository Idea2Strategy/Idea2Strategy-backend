package com.idea2strategy.backend.domain.competition;

import java.math.BigDecimal;
import java.util.Objects;

public record ScoringAdjustmentDefinition(
        String code,
        ScoringAdjustmentUnit unit,
        BigDecimal minimum,
        BigDecimal maximum,
        int scale) {
    public ScoringAdjustmentDefinition {
        code = requireText(code, "code");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(minimum, "minimum");
        Objects.requireNonNull(maximum, "maximum");
        if (minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("minimum must not exceed maximum");
        }
        if (scale < 0 || scale > 8) {
            throw new IllegalArgumentException("scale must be between 0 and 8");
        }
        if (effectiveScale(minimum) > scale || effectiveScale(maximum) > scale) {
            throw new IllegalArgumentException("adjustment bounds exceed declared precision: " + code);
        }
    }

    public void validate(BigDecimal value) {
        Objects.requireNonNull(value, code);
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("adjustment is outside the allowed range: " + code);
        }
        if (effectiveScale(value) > scale) {
            throw new IllegalArgumentException("adjustment exceeds declared precision: " + code);
        }
    }

    private static int effectiveScale(BigDecimal value) {
        return Math.max(0, value.stripTrailingZeros().scale());
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

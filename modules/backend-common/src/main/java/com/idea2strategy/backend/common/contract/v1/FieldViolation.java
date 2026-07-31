package com.idea2strategy.backend.common.contract.v1;

public record FieldViolation(String field, String code) {
    public FieldViolation {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field is required");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code is required");
        }
    }
}

package com.idea2strategy.backend.domain.strategy;

import java.util.Objects;
import java.util.UUID;

public record StrategyElementDefinition(
        UUID id,
        UUID catalogId,
        String elementCode,
        String elementKind,
        String parameterSchema,
        String inputPortSchema,
        String outputPortSchema,
        String executionContract,
        String definitionHash) {
    public StrategyElementDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(catalogId, "catalogId");
        elementCode = requireText(elementCode, "elementCode");
        elementKind = requireText(elementKind, "elementKind");
        parameterSchema = requireText(parameterSchema, "parameterSchema");
        inputPortSchema = requireText(inputPortSchema, "inputPortSchema");
        outputPortSchema = requireText(outputPortSchema, "outputPortSchema");
        executionContract = requireText(executionContract, "executionContract");
        definitionHash = requireText(definitionHash, "definitionHash");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

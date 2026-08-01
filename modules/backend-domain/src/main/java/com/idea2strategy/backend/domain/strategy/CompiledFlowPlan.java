package com.idea2strategy.backend.domain.strategy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record CompiledFlowPlan(
        UUID id,
        UUID elementCatalogVersionId,
        String semanticHash,
        String compilerVersion,
        String requiredFeatureSetHash,
        String planDocument,
        String planHash,
        Instant createdAt) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public CompiledFlowPlan {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(elementCatalogVersionId, "elementCatalogVersionId");
        requireHash(semanticHash, "semanticHash");
        requireText(compilerVersion, "compilerVersion");
        requireHash(requiredFeatureSetHash, "requiredFeatureSetHash");
        requireText(planDocument, "planDocument");
        requireHash(planHash, "planHash");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireHash(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
        }
    }
}

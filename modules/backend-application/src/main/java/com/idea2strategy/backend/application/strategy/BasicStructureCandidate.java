package com.idea2strategy.backend.application.strategy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BasicStructureCandidate(
        UUID id,
        UUID packageId,
        String code,
        String version,
        UUID elementCatalogVersionId,
        String nameDocument,
        String descriptionDocument,
        String flowDocument,
        String contentHash,
        Instant publishedAt) {
    public BasicStructureCandidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(packageId, "packageId");
        code = requireText(code, "code");
        version = requireText(version, "version");
        Objects.requireNonNull(elementCatalogVersionId, "elementCatalogVersionId");
        nameDocument = requireText(nameDocument, "nameDocument");
        descriptionDocument = requireText(descriptionDocument, "descriptionDocument");
        flowDocument = requireText(flowDocument, "flowDocument");
        contentHash = requireText(contentHash, "contentHash");
        Objects.requireNonNull(publishedAt, "publishedAt");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

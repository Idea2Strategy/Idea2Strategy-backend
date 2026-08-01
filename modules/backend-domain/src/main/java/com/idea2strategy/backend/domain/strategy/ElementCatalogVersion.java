package com.idea2strategy.backend.domain.strategy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ElementCatalogVersion(
        UUID id,
        String languageVersion,
        String schemaVersion,
        String catalogVersion,
        String dataRequirementVersion,
        String definitionHash,
        Instant publishedAt,
        Instant retiredAt) {
    public ElementCatalogVersion {
        Objects.requireNonNull(id, "id");
        languageVersion = requireText(languageVersion, "languageVersion");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        catalogVersion = requireText(catalogVersion, "catalogVersion");
        dataRequirementVersion = requireText(dataRequirementVersion, "dataRequirementVersion");
        definitionHash = requireText(definitionHash, "definitionHash");
        Objects.requireNonNull(publishedAt, "publishedAt");
        if (retiredAt != null && retiredAt.isBefore(publishedAt)) {
            throw new IllegalArgumentException("retiredAt must not precede publishedAt");
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

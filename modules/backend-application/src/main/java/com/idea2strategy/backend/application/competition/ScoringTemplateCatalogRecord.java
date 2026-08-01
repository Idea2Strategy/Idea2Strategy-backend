package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ScoringTemplateCatalogRecord(
        UUID id,
        String templateCode,
        String version,
        String rulesDocument,
        String rulesHash,
        Instant publishedAt,
        Instant retiredAt) {
    public ScoringTemplateCatalogRecord {
        Objects.requireNonNull(id, "id");
        templateCode = requireText(templateCode, "templateCode");
        version = requireText(version, "version");
        rulesDocument = requireText(rulesDocument, "rulesDocument");
        rulesHash = requireText(rulesHash, "rulesHash");
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

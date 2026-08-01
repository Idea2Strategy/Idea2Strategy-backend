package com.idea2strategy.backend.application.strategy;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record BasicStructureVersion(
        UUID id,
        UUID packageId,
        String code,
        BasicStructureKind kind,
        String version,
        UUID elementCatalogVersionId,
        Map<String, String> nameI18n,
        Map<String, String> descriptionI18n,
        String flowDocument,
        String contentHash,
        Instant publishedAt) {
    public BasicStructureVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(elementCatalogVersionId, "elementCatalogVersionId");
        nameI18n = Map.copyOf(nameI18n);
        descriptionI18n = Map.copyOf(descriptionI18n);
        Objects.requireNonNull(publishedAt, "publishedAt");
    }
}

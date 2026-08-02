package com.idea2strategy.backend.application.usercase;

import java.util.Objects;
import java.util.UUID;

public record UserCaseEvidenceReference(
        UUID storageObjectId,
        String sourceDomain,
        UUID sourceResourceId) {
    public UserCaseEvidenceReference {
        Objects.requireNonNull(storageObjectId, "storageObjectId");
        if (Objects.requireNonNull(sourceDomain, "sourceDomain").isBlank()) {
            throw new IllegalArgumentException("sourceDomain is required");
        }
        Objects.requireNonNull(sourceResourceId, "sourceResourceId");
    }
}

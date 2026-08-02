package com.idea2strategy.backend.application.usercase;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record VerifiedUserCaseEvidence(
        UUID storageObjectId,
        String sourceDomain,
        UUID sourceResourceId,
        UUID ownerAccountId,
        String ownershipPolicyVersion,
        Instant verifiedAt) {
    public VerifiedUserCaseEvidence {
        Objects.requireNonNull(storageObjectId, "storageObjectId");
        Objects.requireNonNull(sourceDomain, "sourceDomain");
        Objects.requireNonNull(sourceResourceId, "sourceResourceId");
        Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        if (Objects.requireNonNull(ownershipPolicyVersion, "ownershipPolicyVersion").isBlank()) {
            throw new IllegalArgumentException("ownershipPolicyVersion is required");
        }
        Objects.requireNonNull(verifiedAt, "verifiedAt");
    }
}

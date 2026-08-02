package com.idea2strategy.backend.application.usercase;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record UserCaseSupplementCommand(
        UUID accountId,
        UUID caseId,
        long expectedVersion,
        List<UUID> evidenceObjectIds,
        String idempotencyKey,
        String requestHash,
        UUID correlationId) {
    public UserCaseSupplementCommand {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(caseId, "caseId");
        if (expectedVersion <= 0) throw new IllegalArgumentException("expectedVersion must be positive");
        evidenceObjectIds = List.copyOf(Objects.requireNonNull(evidenceObjectIds, "evidenceObjectIds"));
        if (evidenceObjectIds.isEmpty()) throw new IllegalArgumentException("evidenceObjectIds are required");
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey is required");
        if (requestHash == null || requestHash.isBlank()) throw new IllegalArgumentException("requestHash is required");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}

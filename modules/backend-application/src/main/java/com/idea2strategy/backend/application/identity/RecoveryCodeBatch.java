package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RecoveryCodeBatch(
        UUID id, UUID accountId, List<StoredRecoveryCode> codes, Instant issuedAt, UUID correlationId) {
    public RecoveryCodeBatch {
        codes = List.copyOf(codes);
    }
}

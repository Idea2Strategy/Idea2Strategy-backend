package com.idea2strategy.backend.application.usercase;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserCaseView(
        UUID id,
        UUID accountId,
        UserCaseType type,
        UserCaseStatus status,
        long version,
        List<UUID> evidenceObjectIds,
        Instant updatedAt) {
    public UserCaseView {
        evidenceObjectIds = List.copyOf(evidenceObjectIds);
    }
}

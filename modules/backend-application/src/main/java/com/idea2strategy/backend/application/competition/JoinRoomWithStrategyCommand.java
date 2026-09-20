package com.idea2strategy.backend.application.competition;

import java.util.Objects;
import java.util.UUID;

public record JoinRoomWithStrategyCommand(
        UUID roomId,
        UUID validationRunId,
        String anonymousAlias,
        String languageVersion,
        String schemaVersion,
        String catalogVersion,
        int budgetCapBps) {
    public JoinRoomWithStrategyCommand {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(validationRunId, "validationRunId");
        requireText(anonymousAlias, "anonymousAlias");
        requireText(languageVersion, "languageVersion");
        requireText(schemaVersion, "schemaVersion");
        requireText(catalogVersion, "catalogVersion");
        if (budgetCapBps <= 0 || budgetCapBps > 10_000) {
            throw new IllegalArgumentException("budgetCapBps must be in 1..10000");
        }
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

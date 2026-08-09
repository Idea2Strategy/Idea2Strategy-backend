package com.idea2strategy.backend.application.competition;

import java.util.Objects;
import java.util.UUID;

public record RoomFinalizationFailure(UUID roomId, String reason) {
    public RoomFinalizationFailure {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}

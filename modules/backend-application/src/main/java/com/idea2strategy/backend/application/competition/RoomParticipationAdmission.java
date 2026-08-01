package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RoomParticipationAdmission(
        UUID participationId,
        UUID roomId,
        UUID botId,
        UUID ownerAccountId,
        String anonymousAlias,
        Instant joinedAt) {
    public RoomParticipationAdmission {
        Objects.requireNonNull(participationId, "participationId");
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        Objects.requireNonNull(anonymousAlias, "anonymousAlias");
        Objects.requireNonNull(joinedAt, "joinedAt");
    }
}

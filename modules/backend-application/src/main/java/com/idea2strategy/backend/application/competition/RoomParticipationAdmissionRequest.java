package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RoomParticipationAdmissionRequest(
        UUID participationId,
        UUID eventId,
        UUID roomId,
        UUID ownerAccountId,
        String anonymousAlias,
        Instant admittedAt) {
    public RoomParticipationAdmissionRequest {
        Objects.requireNonNull(participationId, "participationId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        Objects.requireNonNull(anonymousAlias, "anonymousAlias");
        Objects.requireNonNull(admittedAt, "admittedAt");
    }
}

package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.competition.RoomOrganizerType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PublicRoomItem(
        UUID id,
        String name,
        RoomOrganizerType organizerType,
        Instant createdAt,
        Instant recruitmentOpensAt,
        Instant participationClosesAt,
        int botParticipationLimit,
        int perAccountBotLimit) {
    public PublicRoomItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(organizerType, "organizerType");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(recruitmentOpensAt, "recruitmentOpensAt");
        Objects.requireNonNull(participationClosesAt, "participationClosesAt");
    }
}

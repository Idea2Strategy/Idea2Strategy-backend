package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.UUID;

public interface RoomTerminationPort {
    RoomTerminationResult withdrawOwned(
            UUID roomId, UUID participationId, UUID ownerAccountId, ParticipationExitAction action,
            String reasonCode, Instant occurredAt);

    RoomTerminationResult cancelOwned(UUID roomId, UUID creatorAccountId, String reasonCode, Instant occurredAt);

    RoomTerminationResult invalidate(
            UUID roomId, UUID operatorId, String reasonCode, Instant occurredAt);
}

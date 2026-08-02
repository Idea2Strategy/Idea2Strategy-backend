package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.UUID;

public interface OperatorRoomAuthorizationPort {
    boolean authorize(
            UUID operatorId,
            String permissionCode,
            String actionType,
            UUID roomId,
            Instant occurredAt);
}

package com.idea2strategy.backend.application.competition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OwnedRoomManagementQueryPort {
    List<OwnedRoomManagementView> findOwnedBy(UUID ownerAccountId, int limit);
    Optional<OwnedRoomManagementView> findOwnedById(UUID ownerAccountId, UUID roomId);
}

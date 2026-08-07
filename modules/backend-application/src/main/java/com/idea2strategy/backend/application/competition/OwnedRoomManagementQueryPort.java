package com.idea2strategy.backend.application.competition;

import java.util.List;
import java.util.UUID;

public interface OwnedRoomManagementQueryPort {
    List<OwnedRoomManagementView> findOwnedBy(UUID ownerAccountId, int limit);
}

package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class OwnedRoomManagementQueryService {
    private final OwnedRoomManagementQueryPort port;
    private final CurrentPrincipal principal;

    public OwnedRoomManagementQueryService(OwnedRoomManagementQueryPort port, CurrentPrincipal principal) {
        this.port = Objects.requireNonNull(port, "port");
        this.principal = Objects.requireNonNull(principal, "principal");
    }

    public List<OwnedRoomManagementView> list(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be in 1..100");
        }
        return port.findOwnedBy(principal.accountId(), limit);
    }

    public Optional<OwnedRoomManagementView> get(UUID roomId) {
        return port.findOwnedById(principal.accountId(), Objects.requireNonNull(roomId, "roomId"));
    }
}

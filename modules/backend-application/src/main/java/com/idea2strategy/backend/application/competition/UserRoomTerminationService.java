package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class UserRoomTerminationService {
    private final RoomTerminationPort port;
    private final CurrentPrincipal principal;
    private final Clock clock;

    public UserRoomTerminationService(RoomTerminationPort port, CurrentPrincipal principal, Clock clock) {
        this.port = Objects.requireNonNull(port, "port");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RoomTerminationResult withdraw(
            UUID roomId, UUID participationId, ParticipationExitAction action, String reasonCode) {
        return port.withdrawOwned(
                Objects.requireNonNull(roomId, "roomId"),
                Objects.requireNonNull(participationId, "participationId"),
                principal.accountId(),
                Objects.requireNonNull(action, "action"),
                requiredReason(reasonCode),
                clock.instant());
    }

    public RoomTerminationResult cancel(UUID roomId, String reasonCode) {
        return port.cancelOwned(
                Objects.requireNonNull(roomId, "roomId"), principal.accountId(), requiredReason(reasonCode), clock.instant());
    }

    private static String requiredReason(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank() || reasonCode.length() > 80) {
            throw new IllegalArgumentException("reasonCode must contain 1 to 80 characters");
        }
        return reasonCode;
    }
}

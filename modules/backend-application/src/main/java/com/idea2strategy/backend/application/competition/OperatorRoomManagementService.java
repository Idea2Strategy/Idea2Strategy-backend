package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.application.common.CurrentOperatorPrincipal;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class OperatorRoomManagementService {
    private final OperatorRoomAuthorizationPort authorization;
    private final OperatorRoomQueryPort queryPort;
    private final RoomTerminationPort terminationPort;
    private final CurrentOperatorPrincipal principal;
    private final Clock clock;

    public OperatorRoomManagementService(
            OperatorRoomAuthorizationPort authorization,
            OperatorRoomQueryPort queryPort,
            RoomTerminationPort terminationPort,
            CurrentOperatorPrincipal principal,
            Clock clock) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.terminationPort = Objects.requireNonNull(terminationPort, "terminationPort");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public OperatorRoomView view(UUID roomId) {
        UUID operatorId = operatorId();
        UUID target = Objects.requireNonNull(roomId, "roomId");
        require(operatorId, OperatorRoomPermissions.READ, "COMPETITION_ROOM_VIEW", target);
        return queryPort.findOfficialRoom(target).orElseThrow(RoomTerminationAccessException::new);
    }

    public RoomTerminationResult cancel(UUID roomId, String reasonCode) {
        UUID operatorId = operatorId();
        UUID target = Objects.requireNonNull(roomId, "roomId");
        require(operatorId, OperatorRoomPermissions.MANAGE, "COMPETITION_ROOM_CANCEL", target);
        return terminationPort.cancelOfficial(
                target, operatorId, requiredReason(reasonCode), clock.instant());
    }

    private UUID operatorId() {
        return principal.operatorId().orElseThrow(OperatorAuthorizationException::new);
    }

    private void require(UUID operatorId, String permission, String action, UUID roomId) {
        if (!authorization.authorize(operatorId, permission, action, roomId, clock.instant())) {
            throw new OperatorAuthorizationException();
        }
    }

    private static String requiredReason(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank() || reasonCode.length() > 80) {
            throw new IllegalArgumentException("reasonCode must contain 1 to 80 characters");
        }
        return reasonCode;
    }
}

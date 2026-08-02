package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.application.common.CurrentOperatorPrincipal;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class PlatformRoomInvalidationService {
    private static final Set<String> ALLOWED_REASON_CODES = Set.of(
            "LEGAL_REQUIREMENT", "SYSTEM_SAFETY", "OFFICIAL_LEDGER_INTEGRITY");
    private final RoomTerminationPort port;
    private final CurrentOperatorPrincipal principal;
    private final Clock clock;

    public PlatformRoomInvalidationService(RoomTerminationPort port, CurrentOperatorPrincipal principal, Clock clock) {
        this.port = Objects.requireNonNull(port, "port");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RoomTerminationResult invalidate(UUID roomId, String reasonCode) {
        UUID operatorId = principal.operatorId().orElseThrow(OperatorAuthorizationException::new);
        if (reasonCode == null || reasonCode.isBlank() || reasonCode.length() > 80) {
            throw new IllegalArgumentException("reasonCode must contain 1 to 80 characters");
        }
        if (!ALLOWED_REASON_CODES.contains(reasonCode)) {
            throw new IllegalArgumentException(
                    "reasonCode must identify a legal, safety, or ledger-integrity requirement");
        }
        return port.invalidate(Objects.requireNonNull(roomId, "roomId"), operatorId, reasonCode, clock.instant());
    }
}

package com.idea2strategy.backend.application.botcontrol;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class BotStopCommandService {
    private final BotStopCommandPort commandPort;
    private final CurrentPrincipal principal;
    private final Clock clock;

    public BotStopCommandService(
            BotStopCommandPort commandPort, CurrentPrincipal principal, Clock clock) {
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BotStopDispatch issue(UUID botId, String reasonCode) {
        Objects.requireNonNull(botId, "botId");
        String normalizedReason = normalizeReason(reasonCode);
        return commandPort.issueOwned(botId, principal.accountId(), normalizedReason, clock.instant())
                .orElseThrow(BotExecutionPreflightNotFoundException::new);
    }

    private static String normalizeReason(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        String normalized = reasonCode.trim();
        if (normalized.length() > 80 || !normalized.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("reasonCode must be an uppercase code of at most 80 characters");
        }
        return normalized;
    }
}

package com.idea2strategy.backend.application.botoperations;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class BotDeletionCommandService {
    private final BotDeletionCommandPort commandPort;
    private final CurrentPrincipal principal;
    private final Clock clock;

    public BotDeletionCommandService(
            BotDeletionCommandPort commandPort, CurrentPrincipal principal, Clock clock) {
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void delete(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        BotDeletionResult result = commandPort.deleteOwnedStopped(
                botId, principal.accountId(), clock.instant());
        if (result == BotDeletionResult.NOT_FOUND) {
            throw new BotOperationsNotFoundException(botId);
        }
        if (result == BotDeletionResult.NOT_STOPPED) {
            throw new BotDeletionConflictException();
        }
    }
}

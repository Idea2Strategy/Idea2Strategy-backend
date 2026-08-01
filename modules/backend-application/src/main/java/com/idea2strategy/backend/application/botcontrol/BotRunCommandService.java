package com.idea2strategy.backend.application.botcontrol;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class BotRunCommandService {
    private final BotRunCommandPort commandPort;
    private final BotExecutionPreflightService preflightService;
    private final CurrentPrincipal principal;
    private final Clock clock;

    public BotRunCommandService(
            BotRunCommandPort commandPort,
            BotExecutionPreflightService preflightService,
            CurrentPrincipal principal,
            Clock clock) {
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.preflightService = Objects.requireNonNull(preflightService, "preflightService");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BotRunDispatch issue(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        var report = preflightService.validate(botId);
        if (!report.ready()) {
            throw new BotRunCommandRejectedException(report.issues());
        }
        return commandPort.issueOwned(botId, principal.accountId(), clock.instant())
                .orElseThrow(BotExecutionPreflightNotFoundException::new);
    }
}

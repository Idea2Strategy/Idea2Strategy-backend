package com.idea2strategy.backend.application.botcontrol;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class BotContinuationService {
    private static final Duration RENEWAL_WINDOW = Duration.ofDays(7);

    private final BotContinuationQueryPort queryPort;
    private final BotContinuationCommandPort commandPort;
    private final CurrentPrincipal principal;
    private final Clock clock;

    public BotContinuationService(
            BotContinuationQueryPort queryPort,
            BotContinuationCommandPort commandPort,
            CurrentPrincipal principal,
            Clock clock) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BotContinuationView get(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        BotContinuationFacts facts = queryPort.findOwned(botId, principal.accountId())
                .orElseThrow(BotContinuationNotFoundException::new);
        return toView(facts, clock.instant());
    }

    public BotContinuationView renew(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        Instant receivedAt = clock.instant();
        BotContinuationFacts facts = commandPort.renewOwned(botId, principal.accountId(), receivedAt)
                .orElseThrow(BotContinuationNotFoundException::new);
        return toView(facts, receivedAt);
    }

    private static BotContinuationView toView(BotContinuationFacts facts, Instant now) {
        Instant availableFrom = facts.dueAt().minus(RENEWAL_WINDOW);
        boolean allowed = !now.isBefore(availableFrom) && now.isBefore(facts.dueAt());
        return new BotContinuationView(
                facts.botId(), facts.dueAt(), availableFrom, facts.lastRenewedAt(), allowed);
    }
}

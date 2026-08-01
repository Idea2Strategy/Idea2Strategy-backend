package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.common.DomainEventPublisher;
import com.idea2strategy.backend.application.common.IdGenerator;
import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyCreated;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class StrategyCommandService {
    private final StrategyCommandPort commandPort;
    private final CurrentPrincipal principal;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final DomainEventPublisher eventPublisher;

    public StrategyCommandService(
            StrategyCommandPort commandPort,
            CurrentPrincipal principal,
            IdGenerator idGenerator,
            Clock clock,
            DomainEventPublisher eventPublisher) {
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    public UUID createBasic(String name, String description) {
        Instant now = clock.instant();
        Strategy strategy = Strategy.createBasic(idGenerator.nextId(), principal.accountId(), name, description, now);
        commandPort.save(strategy);
        eventPublisher.publish(new StrategyCreated(strategy.id(), strategy.ownerAccountId(), strategy.mode(), now));
        return strategy.id();
    }
}

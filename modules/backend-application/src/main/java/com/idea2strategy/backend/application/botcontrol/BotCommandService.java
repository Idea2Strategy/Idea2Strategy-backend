package com.idea2strategy.backend.application.botcontrol;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.common.DomainEventPublisher;
import com.idea2strategy.backend.application.common.IdGenerator;
import com.idea2strategy.backend.domain.botcontrol.Bot;
import com.idea2strategy.backend.domain.botcontrol.BotStarted;
import com.idea2strategy.backend.domain.botcontrol.BotStopRequested;
import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public final class BotCommandService {
    private final BotCommandPort commandPort;
    private final BotQueryPort queryPort;
    private final CurrentPrincipal principal;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final DomainEventPublisher eventPublisher;

    public BotCommandService(
            BotCommandPort commandPort,
            BotQueryPort queryPort,
            CurrentPrincipal principal,
            IdGenerator idGenerator,
            Clock clock,
            DomainEventPublisher eventPublisher) {
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    public UUID startBasic(String name) {
        Instant now = clock.instant();
        Bot bot = Bot.startBasic(idGenerator.nextId(), principal.accountId(), name, now);
        commandPort.save(bot);
        eventPublisher.publish(new BotStarted(bot.id(), bot.ownerAccountId(), now));
        return bot.id();
    }

    public void requestStop(UUID botId, String reasonCode) {
        Bot bot = queryPort.findOwnedById(botId, principal.accountId())
                .orElseThrow(() -> new NoSuchElementException("Bot not found"));
        Instant now = clock.instant();
        Bot stopping = bot.requestStop(now, reasonCode);
        commandPort.save(stopping);
        eventPublisher.publish(new BotStopRequested(botId, principal.accountId(), reasonCode, now));
    }
}

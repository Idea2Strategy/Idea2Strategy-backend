package com.idea2strategy.backend.application.botcontrol;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.domain.botcontrol.Bot;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public final class BotQueryService {
    private final BotQueryPort queryPort;
    private final CurrentPrincipal principal;

    public BotQueryService(BotQueryPort queryPort, CurrentPrincipal principal) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.principal = Objects.requireNonNull(principal, "principal");
    }

    public Bot getOwned(UUID botId) {
        return queryPort.findOwnedById(botId, principal.accountId())
                .orElseThrow(() -> new NoSuchElementException("Bot not found"));
    }
}

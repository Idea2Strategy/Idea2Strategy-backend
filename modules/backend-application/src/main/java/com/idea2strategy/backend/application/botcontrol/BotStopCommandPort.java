package com.idea2strategy.backend.application.botcontrol;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface BotStopCommandPort {
    Optional<BotStopDispatch> issueOwned(
            UUID botId, UUID ownerAccountId, String reasonCode, Instant requestedAt);
}

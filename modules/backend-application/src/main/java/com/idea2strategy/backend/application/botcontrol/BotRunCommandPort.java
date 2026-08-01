package com.idea2strategy.backend.application.botcontrol;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface BotRunCommandPort {
    Optional<BotRunDispatch> issueOwned(UUID botId, UUID ownerAccountId, Instant requestedAt);
}

package com.idea2strategy.backend.application.botcontrol;

import com.idea2strategy.backend.domain.botcontrol.Bot;
import java.util.Optional;
import java.util.UUID;

public interface BotQueryPort {
    Optional<Bot> findOwnedById(UUID botId, UUID ownerAccountId);
}

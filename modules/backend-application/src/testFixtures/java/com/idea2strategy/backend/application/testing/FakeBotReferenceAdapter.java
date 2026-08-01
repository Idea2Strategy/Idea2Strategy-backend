package com.idea2strategy.backend.application.testing;

import com.idea2strategy.backend.application.competition.BotReferencePort;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class FakeBotReferenceAdapter implements BotReferencePort {
    private final Set<UUID> botIds = new HashSet<>();

    public void add(UUID botId) {
        botIds.add(botId);
    }

    @Override
    public boolean exists(UUID botId) {
        return botIds.contains(botId);
    }
}

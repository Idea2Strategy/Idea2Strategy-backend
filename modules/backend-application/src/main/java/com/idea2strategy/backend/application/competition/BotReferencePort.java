package com.idea2strategy.backend.application.competition;

import java.util.UUID;

public interface BotReferencePort {
    boolean exists(UUID botId);
}

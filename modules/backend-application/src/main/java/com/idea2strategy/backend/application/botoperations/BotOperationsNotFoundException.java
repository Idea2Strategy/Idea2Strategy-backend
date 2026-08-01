package com.idea2strategy.backend.application.botoperations;

import java.util.UUID;

public final class BotOperationsNotFoundException extends RuntimeException {
    public BotOperationsNotFoundException(UUID botId) {
        super("Bot operations were not found for bot " + botId);
    }
}

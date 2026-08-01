package com.idea2strategy.backend.application.botcontrol;

public final class BotContinuationNotFoundException extends RuntimeException {
    public BotContinuationNotFoundException() {
        super("Bot continuation deadline not found");
    }
}

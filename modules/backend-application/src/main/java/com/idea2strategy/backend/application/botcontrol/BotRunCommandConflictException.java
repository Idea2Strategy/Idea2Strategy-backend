package com.idea2strategy.backend.application.botcontrol;

public final class BotRunCommandConflictException extends IllegalStateException {
    public BotRunCommandConflictException(String message) {
        super(message);
    }
}

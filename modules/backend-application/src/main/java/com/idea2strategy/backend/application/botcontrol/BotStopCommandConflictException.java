package com.idea2strategy.backend.application.botcontrol;

public final class BotStopCommandConflictException extends IllegalStateException {
    public BotStopCommandConflictException(String message) {
        super(message);
    }
}

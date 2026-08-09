package com.idea2strategy.backend.application.botoperations;

public final class BotDeletionConflictException extends RuntimeException {
    public BotDeletionConflictException() {
        super("Bot deletion is allowed only after permanent stop and settlement");
    }
}

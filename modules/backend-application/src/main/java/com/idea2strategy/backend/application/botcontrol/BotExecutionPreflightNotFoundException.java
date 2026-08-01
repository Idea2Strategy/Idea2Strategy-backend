package com.idea2strategy.backend.application.botcontrol;

import java.util.NoSuchElementException;

public final class BotExecutionPreflightNotFoundException extends NoSuchElementException {
    public BotExecutionPreflightNotFoundException() {
        super("Bot not found");
    }
}

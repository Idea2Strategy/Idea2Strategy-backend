package com.idea2strategy.backend.application.identity;

public final class SanctionedAccountAccessException extends RuntimeException {
    public SanctionedAccountAccessException() {
        super("An account sanction is active; appeal access remains available");
    }
}

package com.idea2strategy.backend.application.competition;

public final class OperatorAuthorizationException extends RuntimeException {
    public OperatorAuthorizationException() {
        super("Platform operator authorization is required");
    }
}

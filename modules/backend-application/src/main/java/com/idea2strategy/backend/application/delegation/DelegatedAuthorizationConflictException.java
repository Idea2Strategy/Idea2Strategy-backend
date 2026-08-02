package com.idea2strategy.backend.application.delegation;

public final class DelegatedAuthorizationConflictException extends RuntimeException {
    public DelegatedAuthorizationConflictException() {
        super("Delegated authorization changed concurrently");
    }
}

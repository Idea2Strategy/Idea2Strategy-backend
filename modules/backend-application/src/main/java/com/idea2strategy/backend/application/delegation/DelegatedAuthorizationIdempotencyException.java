package com.idea2strategy.backend.application.delegation;

public final class DelegatedAuthorizationIdempotencyException extends RuntimeException {
    public DelegatedAuthorizationIdempotencyException() {
        super("Idempotency key was already used for different delegated authorization content");
    }
}

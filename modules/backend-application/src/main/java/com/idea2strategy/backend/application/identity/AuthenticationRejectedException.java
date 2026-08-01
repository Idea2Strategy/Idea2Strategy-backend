package com.idea2strategy.backend.application.identity;

public final class AuthenticationRejectedException extends RuntimeException {
    public AuthenticationRejectedException(String message) {
        super(message);
    }
}

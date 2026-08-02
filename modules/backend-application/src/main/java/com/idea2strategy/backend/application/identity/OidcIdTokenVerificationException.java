package com.idea2strategy.backend.application.identity;

public final class OidcIdTokenVerificationException extends RuntimeException {
    public OidcIdTokenVerificationException(String message) {
        super(message);
    }

    public OidcIdTokenVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}

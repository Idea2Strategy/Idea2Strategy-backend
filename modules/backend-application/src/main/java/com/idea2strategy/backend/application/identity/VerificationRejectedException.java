package com.idea2strategy.backend.application.identity;

public final class VerificationRejectedException extends RuntimeException {
    public VerificationRejectedException(String message) {
        super(message);
    }
}

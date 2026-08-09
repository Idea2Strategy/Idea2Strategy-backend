package com.idea2strategy.backend.application.identity;

public final class VerificationRateLimitedException extends RuntimeException {
    public VerificationRateLimitedException() {
        super("Verification email request limit exceeded");
    }
}

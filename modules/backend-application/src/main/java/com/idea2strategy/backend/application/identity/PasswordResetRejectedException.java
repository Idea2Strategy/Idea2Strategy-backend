package com.idea2strategy.backend.application.identity;

public final class PasswordResetRejectedException extends RuntimeException {
    public PasswordResetRejectedException() {
        super("Password reset token is invalid or no longer usable");
    }
}

package com.idea2strategy.backend.application.identity;

@FunctionalInterface
public interface PasswordResetTokenDigest {
    String digest(String rawToken);
}

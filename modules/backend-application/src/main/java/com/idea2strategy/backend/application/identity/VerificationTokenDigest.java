package com.idea2strategy.backend.application.identity;

@FunctionalInterface
public interface VerificationTokenDigest {
    String digest(String rawToken);
}

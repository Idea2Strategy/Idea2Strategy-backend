package com.idea2strategy.backend.application.identity;

@FunctionalInterface
public interface VerificationTokenIssuer {
    VerificationToken issue();
}

package com.idea2strategy.backend.application.identity;

@FunctionalInterface
public interface RefreshTokenSecretIssuer {
    RefreshTokenSecret issue();
}

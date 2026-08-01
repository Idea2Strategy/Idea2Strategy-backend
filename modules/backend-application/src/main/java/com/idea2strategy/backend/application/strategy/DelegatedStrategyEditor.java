package com.idea2strategy.backend.application.strategy;

import java.util.Objects;
import java.util.UUID;

public record DelegatedStrategyEditor(UUID accountId, UUID authorizationId, UUID credentialId) {
    public DelegatedStrategyEditor {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(credentialId, "credentialId");
    }
}

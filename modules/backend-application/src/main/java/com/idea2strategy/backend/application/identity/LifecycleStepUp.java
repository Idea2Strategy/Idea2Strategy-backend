package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;

public record LifecycleStepUp(
        UUID accountId,
        AccountLifecycleAuthenticationProof proof) {
    public LifecycleStepUp {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(proof, "proof");
    }
}

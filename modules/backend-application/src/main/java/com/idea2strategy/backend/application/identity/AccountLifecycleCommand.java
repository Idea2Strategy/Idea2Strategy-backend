package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;

public record AccountLifecycleCommand(
        UUID accountId,
        String idempotencyKey,
        String requestHash,
        UUID correlationId,
        AccountLifecycleAuthenticationProof proof) {
    public AccountLifecycleCommand {
        Objects.requireNonNull(accountId, "accountId");
        if (Objects.requireNonNull(idempotencyKey, "idempotencyKey").isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        if (Objects.requireNonNull(requestHash, "requestHash").isBlank()) {
            throw new IllegalArgumentException("requestHash must not be blank");
        }
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(proof, "proof");
    }
}

package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;
import java.util.Set;

public record AccountLifecycleCommand(
        UUID accountId,
        String idempotencyKey,
        String requestHash,
        UUID correlationId,
        AccountLifecycleAuthenticationProof proof,
        Set<UUID> acceptedPolicyDocumentIds) {
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
        acceptedPolicyDocumentIds = Set.copyOf(Objects.requireNonNull(
                acceptedPolicyDocumentIds, "acceptedPolicyDocumentIds"));
    }

    public AccountLifecycleCommand(
            UUID accountId,
            String idempotencyKey,
            String requestHash,
            UUID correlationId,
            AccountLifecycleAuthenticationProof proof) {
        this(accountId, idempotencyKey, requestHash, correlationId, proof, Set.of());
    }
}

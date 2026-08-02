package com.idea2strategy.backend.application.delegation;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record DelegatedAuthorizationCommand(
        DelegatedAuthorizationCommandType commandType,
        UUID accountId,
        UUID authorizationId,
        UUID replacesAuthorizationId,
        long expectedAuthorizationVersion,
        long authEpochAtGrant,
        String clientLabel,
        UUID disclosurePolicyDocumentId,
        Set<DelegatedAuthorizationScope> scopes,
        Set<UUID> targetStrategyIds,
        Instant expiresAt,
        String reasonCode,
        String idempotencyKey,
        String requestHash,
        UUID correlationId) {
    public DelegatedAuthorizationCommand {
        Objects.requireNonNull(commandType, "commandType");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(disclosurePolicyDocumentId, "disclosurePolicyDocumentId");
        scopes = Set.copyOf(Objects.requireNonNull(scopes, "scopes"));
        targetStrategyIds = Set.copyOf(Objects.requireNonNull(targetStrategyIds, "targetStrategyIds"));
        requireText(clientLabel, "clientLabel");
        requireText(idempotencyKey, "idempotencyKey");
        requireText(requestHash, "requestHash");
        Objects.requireNonNull(correlationId, "correlationId");
        if (expectedAuthorizationVersion < 0 || authEpochAtGrant < 1) {
            throw new IllegalArgumentException("authorization versions and epochs must be valid");
        }
        switch (commandType) {
            case CREATE -> {
                if (replacesAuthorizationId != null || expectedAuthorizationVersion != 0 || scopes.isEmpty()) {
                    throw new IllegalArgumentException("CREATE must start version one with scopes and no predecessor");
                }
            }
            case REPLACE -> {
                if (replacesAuthorizationId == null
                        || replacesAuthorizationId.equals(authorizationId)
                        || expectedAuthorizationVersion < 1
                        || scopes.isEmpty()) {
                    throw new IllegalArgumentException("REPLACE requires a distinct predecessor and scopes");
                }
            }
            case REVOKE -> {
                if (replacesAuthorizationId != null
                        || expectedAuthorizationVersion < 1
                        || !scopes.isEmpty()
                        || !targetStrategyIds.isEmpty()
                        || reasonCode == null
                        || reasonCode.isBlank()) {
                    throw new IllegalArgumentException("REVOKE requires a reason and cannot change grant contents");
                }
            }
        }
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

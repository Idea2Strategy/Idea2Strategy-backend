package com.idea2strategy.backend.application.delegation;

import com.idea2strategy.backend.application.strategy.DelegatedStrategyEditor;
import java.util.Objects;
import java.util.UUID;

public record DelegatedStrategyDerivationCommand(
        DelegatedStrategyDerivationType derivationType,
        DelegatedStrategyEditor editor,
        long expectedAuthorizationVersion,
        UUID sourceStrategyId,
        UUID resultStrategyId,
        long resultStrategyAccessEpoch,
        UUID correlationId,
        String idempotencyKey,
        String requestHash) {
    public DelegatedStrategyDerivationCommand {
        Objects.requireNonNull(derivationType, "derivationType");
        Objects.requireNonNull(editor, "editor");
        Objects.requireNonNull(resultStrategyId, "resultStrategyId");
        Objects.requireNonNull(correlationId, "correlationId");
        requireText(idempotencyKey, "idempotencyKey");
        requireText(requestHash, "requestHash");
        if (expectedAuthorizationVersion < 1 || resultStrategyAccessEpoch < 1) {
            throw new IllegalArgumentException("authorization version and Strategy epoch must be positive");
        }
        if (derivationType == DelegatedStrategyDerivationType.CREATE && sourceStrategyId != null) {
            throw new IllegalArgumentException("CREATE cannot have a source Strategy");
        }
        if (derivationType == DelegatedStrategyDerivationType.COPY
                && (sourceStrategyId == null || sourceStrategyId.equals(resultStrategyId))) {
            throw new IllegalArgumentException("COPY requires a distinct explicit source Strategy");
        }
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}

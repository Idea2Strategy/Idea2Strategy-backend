package com.idea2strategy.backend.application.accountsanction;

import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccountSanctionCommand(
        Type type,
        OperatorRequestContext requestContext,
        UUID accountId,
        UUID sanctionId,
        AccountSanctionState.Type sanctionType,
        String reasonCode,
        Instant expiresAt,
        UUID sourceCaseId,
        UUID correlationId,
        String idempotencyKey,
        String requestHash,
        long expectedVersion) {

    public AccountSanctionCommand {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(sanctionId, "sanctionId");
        Objects.requireNonNull(correlationId, "correlationId");
        requireText(reasonCode, "reasonCode");
        requireText(idempotencyKey, "idempotencyKey");
        requireText(requestHash, "requestHash");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (type == Type.APPLY) {
            Objects.requireNonNull(requestContext, "requestContext");
            Objects.requireNonNull(sanctionType, "sanctionType");
            if (sanctionType == AccountSanctionState.Type.SUSPENSION && expiresAt == null) {
                throw new IllegalArgumentException("temporary sanctions require expiresAt");
            }
            if (sanctionType == AccountSanctionState.Type.PERMANENT && expiresAt != null) {
                throw new IllegalArgumentException("permanent sanctions cannot expire");
            }
        } else {
            if (sanctionType != null || expiresAt != null) {
                throw new IllegalArgumentException("only apply may define sanction type or expiry");
            }
            if (type == Type.LIFT) {
                Objects.requireNonNull(requestContext, "requestContext");
            } else if (requestContext != null) {
                throw new IllegalArgumentException("system expiry cannot carry an operator context");
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public enum Type {
        APPLY,
        LIFT,
        EXPIRE
    }
}

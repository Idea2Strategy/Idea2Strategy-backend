package com.idea2strategy.backend.application.operatorrbac;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OperatorRequestContext(
        UUID operatorId,
        boolean sessionAuthenticated,
        boolean mfaCompleted,
        Instant mfaAuthenticatedAt,
        UUID sessionId) {
    public OperatorRequestContext {
        Objects.requireNonNull(operatorId, "operatorId");
        if (!mfaCompleted && mfaAuthenticatedAt != null) {
            throw new IllegalArgumentException("MFA authentication time requires current MFA");
        }
    }

    public OperatorRequestContext(
            UUID operatorId, boolean sessionAuthenticated, boolean mfaCompleted) {
        this(operatorId, sessionAuthenticated, mfaCompleted, null, null);
    }

    public OperatorRequestContext(
            UUID operatorId, boolean sessionAuthenticated, boolean mfaCompleted,
            Instant mfaAuthenticatedAt) {
        this(operatorId, sessionAuthenticated, mfaCompleted, mfaAuthenticatedAt, null);
    }
}

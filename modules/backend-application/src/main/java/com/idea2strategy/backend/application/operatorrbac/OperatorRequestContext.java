package com.idea2strategy.backend.application.operatorrbac;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OperatorRequestContext(
        UUID operatorId,
        boolean trustedExternalSubject,
        boolean mfaCompleted,
        Instant mfaAuthenticatedAt) {
    public OperatorRequestContext {
        Objects.requireNonNull(operatorId, "operatorId");
        if (!mfaCompleted && mfaAuthenticatedAt != null) {
            throw new IllegalArgumentException("MFA authentication time requires current MFA");
        }
    }

    public OperatorRequestContext(
            UUID operatorId, boolean trustedExternalSubject, boolean mfaCompleted) {
        this(operatorId, trustedExternalSubject, mfaCompleted, null);
    }
}

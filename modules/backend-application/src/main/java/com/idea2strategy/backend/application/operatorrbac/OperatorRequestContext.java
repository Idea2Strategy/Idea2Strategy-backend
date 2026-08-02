package com.idea2strategy.backend.application.operatorrbac;

import java.util.Objects;
import java.util.UUID;

public record OperatorRequestContext(
        UUID operatorId,
        boolean trustedExternalSubject,
        boolean mfaCompleted) {
    public OperatorRequestContext {
        Objects.requireNonNull(operatorId, "operatorId");
    }
}

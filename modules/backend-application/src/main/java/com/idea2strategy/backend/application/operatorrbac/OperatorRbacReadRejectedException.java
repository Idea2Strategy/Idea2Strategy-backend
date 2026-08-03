package com.idea2strategy.backend.application.operatorrbac;

import java.util.Objects;
import java.util.UUID;

public final class OperatorRbacReadRejectedException extends RuntimeException {
    private final Reason reason;
    private final UUID correlationId;

    public OperatorRbacReadRejectedException(Reason reason, String code, UUID correlationId) {
        super(Objects.requireNonNull(code, "code"));
        this.reason = Objects.requireNonNull(reason, "reason");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
    }

    public Reason reason() { return reason; }
    public UUID correlationId() { return correlationId; }

    public enum Reason { UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, CONFLICT }
}

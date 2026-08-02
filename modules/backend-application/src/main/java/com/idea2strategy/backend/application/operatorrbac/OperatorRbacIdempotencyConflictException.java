package com.idea2strategy.backend.application.operatorrbac;

public final class OperatorRbacIdempotencyConflictException extends RuntimeException {
    public OperatorRbacIdempotencyConflictException() {
        super("OPERATOR_RBAC_IDEMPOTENCY_CONFLICT");
    }
}

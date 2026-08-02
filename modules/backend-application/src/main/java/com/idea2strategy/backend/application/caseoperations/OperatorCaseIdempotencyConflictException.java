package com.idea2strategy.backend.application.caseoperations;

public final class OperatorCaseIdempotencyConflictException extends RuntimeException {
    public OperatorCaseIdempotencyConflictException() {
        super("OPERATOR_CASE_IDEMPOTENCY_CONFLICT");
    }
}

package com.idea2strategy.backend.api.caseoperations;

import java.util.UUID;

final class OperatorCaseRejectedException extends RuntimeException {
    private final UUID correlationId;
    private final int status;

    OperatorCaseRejectedException(String code, UUID correlationId, int status) {
        super(code);
        this.correlationId = correlationId;
        this.status = status;
    }

    UUID correlationId() {
        return correlationId;
    }

    int status() {
        return status;
    }
}

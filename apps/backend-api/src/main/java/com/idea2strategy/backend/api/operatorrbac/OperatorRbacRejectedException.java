package com.idea2strategy.backend.api.operatorrbac;

import java.util.UUID;

final class OperatorRbacRejectedException extends RuntimeException {
    private final UUID correlationId;
    private final int responseStatus;

    OperatorRbacRejectedException(String code, UUID correlationId, int responseStatus) {
        super(code);
        this.correlationId = correlationId;
        this.responseStatus = responseStatus;
    }

    UUID correlationId() { return correlationId; }
    int responseStatus() { return responseStatus; }
}

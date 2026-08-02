package com.idea2strategy.backend.api.sanction;

import java.util.UUID;

final class AccountSanctionRejectedException extends RuntimeException {
    private final UUID correlationId;

    AccountSanctionRejectedException(String code, UUID correlationId) {
        super(code);
        this.correlationId = correlationId;
    }

    UUID correlationId() {
        return correlationId;
    }
}

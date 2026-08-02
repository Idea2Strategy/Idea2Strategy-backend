package com.idea2strategy.backend.api.identity;

import java.util.Objects;
import java.util.UUID;

final class LifecycleRequestRejectedException extends RuntimeException {
    private final String code;
    private final UUID correlationId;

    LifecycleRequestRejectedException(String code, UUID correlationId) {
        super(code);
        this.code = Objects.requireNonNull(code, "code");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
    }

    String code() {
        return code;
    }

    UUID correlationId() {
        return correlationId;
    }
}

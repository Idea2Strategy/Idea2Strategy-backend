package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;

public record RecordConsentDecision(
        UUID policyDocumentId,
        String decision,
        UUID correlationId) {
    public RecordConsentDecision {
        Objects.requireNonNull(policyDocumentId, "policyDocumentId");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}

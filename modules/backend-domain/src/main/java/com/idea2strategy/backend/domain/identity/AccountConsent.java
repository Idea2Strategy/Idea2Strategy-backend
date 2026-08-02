package com.idea2strategy.backend.domain.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccountConsent(
        UUID id,
        UUID accountId,
        UUID policyDocumentId,
        ConsentDecision decision,
        UUID supersedesConsentId,
        Instant recordedAt) {
    public AccountConsent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(policyDocumentId, "policyDocumentId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (id.equals(supersedesConsentId)) {
            throw new IllegalArgumentException("A consent decision cannot supersede itself");
        }
    }
}

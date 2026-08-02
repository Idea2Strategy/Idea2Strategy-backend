package com.idea2strategy.backend.application.identity;

import com.idea2strategy.backend.domain.identity.AccountConsent;
import com.idea2strategy.backend.domain.identity.PolicyDocumentVersion;
import java.util.Objects;
import java.util.Optional;

public record CurrentPolicyDecision(
        PolicyDocumentVersion document,
        Optional<AccountConsent> latestDecision) {
    public CurrentPolicyDecision {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(latestDecision, "latestDecision");
        latestDecision.ifPresent(consent -> {
            if (!document.id().equals(consent.policyDocumentId())) {
                throw new IllegalArgumentException("A decision must target the exact policy document");
            }
        });
    }
}

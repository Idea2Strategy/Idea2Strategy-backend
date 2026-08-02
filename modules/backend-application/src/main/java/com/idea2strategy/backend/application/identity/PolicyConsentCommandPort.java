package com.idea2strategy.backend.application.identity;

import com.idea2strategy.backend.domain.identity.ConsentDecision;
import java.time.Instant;
import java.util.UUID;

public interface PolicyConsentCommandPort {
    ConsentDecisionResult recordDecision(
            UUID accountId,
            UUID policyDocumentId,
            ConsentDecision decision,
            UUID correlationId,
            Instant recordedAt);

    void recordConsentRejection(UUID accountId, String reasonCode, UUID correlationId, Instant occurredAt);
}

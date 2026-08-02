package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.UUID;
import java.util.Set;

public interface AccountReactivationEligibilityPort {
    AccountReactivationEligibility evaluateAndConsume(
            UUID accountId,
            AccountLifecycleAuthenticationProof proof,
            Set<UUID> acceptedPolicyDocumentIds,
            UUID correlationId,
            Instant now);
}

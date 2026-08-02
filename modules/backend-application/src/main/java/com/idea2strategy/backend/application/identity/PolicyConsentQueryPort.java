package com.idea2strategy.backend.application.identity;

import com.idea2strategy.backend.domain.identity.AccountConsent;
import com.idea2strategy.backend.domain.identity.PolicyDocumentVersion;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyConsentQueryPort {
    List<PolicyDocumentVersion> findCurrentPolicies(String languageCode, Instant now);

    Optional<AccountConsent> findLatestConsent(UUID accountId, UUID policyDocumentId);

    List<AccountConsent> findConsentHistory(UUID accountId, UUID policyDocumentId);
}

package com.idea2strategy.backend.application.identity;

import com.idea2strategy.backend.domain.identity.AccountPreferences;
import java.time.Instant;
import java.util.UUID;

public interface AccountPreferencesCommandPort {
    AccountPreferences update(UUID accountId, AccountPreferences preferences, UUID correlationId);

    void recordPreferenceRejection(UUID accountId, String reasonCode, UUID correlationId, Instant occurredAt);
}

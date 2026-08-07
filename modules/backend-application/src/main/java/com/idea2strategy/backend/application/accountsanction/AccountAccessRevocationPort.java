package com.idea2strategy.backend.application.accountsanction;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface AccountAccessRevocationPort {
    void revoke(Effect effect);

    record Effect(
            UUID accountId,
            UUID sanctionId,
            boolean bumpAuthEpoch,
            boolean revokeAllCredentials,
            String reasonCode,
            UUID correlationId,
            Instant occurredAt) {

        public Effect {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(sanctionId, "sanctionId");
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }
}

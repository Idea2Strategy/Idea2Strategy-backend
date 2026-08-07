package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.UUID;

public record RotatedSession(
        UUID accountId,
        UUID loginIdentityId,
        long authEpoch,
        Long credentialVersion,
        UUID familyId,
        String tokenSecret,
        Instant expiresAt) {
    @Override
    public String toString() {
        return "RotatedSession[accountId=" + accountId + ",familyId=" + familyId
                + ",tokenSecret=REDACTED,expiresAt=" + expiresAt + "]";
    }
}

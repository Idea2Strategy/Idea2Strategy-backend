package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OidcStepUpChallengePort {
    void create(
            UUID challengeId,
            short providerId,
            String nonceDigest,
            short digestKeyVersion,
            Instant requestedAt,
            Instant expiresAt);

    Optional<StoredOidcStepUpChallenge> find(UUID challengeId);

    boolean registerVerificationAttempt(UUID challengeId, Instant attemptedAt);
}

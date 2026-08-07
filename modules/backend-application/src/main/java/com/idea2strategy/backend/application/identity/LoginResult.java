package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.UUID;

public record LoginResult(
        UUID accountId,
        UUID loginIdentityId,
        long authEpoch,
        Long credentialVersion,
        UUID refreshTokenFamilyId,
        String refreshTokenSecret,
        Instant expiresAt) {}

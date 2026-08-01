package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.UUID;

public record PendingPasswordReset(
        UUID id,
        UUID accountId,
        UUID loginIdentityId,
        long authEpoch,
        long credentialVersion,
        String tokenDigest,
        Instant requestedAt,
        Instant expiresAt,
        UUID correlationId,
        String requestIpPrefix) {}

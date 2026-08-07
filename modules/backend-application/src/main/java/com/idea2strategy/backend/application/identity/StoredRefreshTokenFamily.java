package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.UUID;

public record StoredRefreshTokenFamily(
        UUID id,
        UUID accountId,
        UUID loginIdentityId,
        long authEpochAtIssue,
        long currentAuthEpoch,
        Long credentialVersionAtIssue,
        Long currentCredentialVersion,
        AccountLifecycleStatus accountStatus,
        LoginIdentityStatus loginIdentityStatus,
        Instant issuedAt,
        Instant lastRotatedAt,
        Instant expiresAt,
        Instant revokedAt,
        boolean activeSanction) {}

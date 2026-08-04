package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.UUID;

public record StoredSession(
        UUID id,
        UUID accountId,
        UUID loginIdentityId,
        long authEpochAtIssue,
        long currentAuthEpoch,
        Long credentialVersionAtIssue,
        Long currentCredentialVersion,
        AccountLifecycleStatus accountStatus,
        LoginIdentityStatus loginIdentityStatus,
        String deviceLabel,
        Instant issuedAt,
        Instant lastSeenAt,
        Instant expiresAt,
        Instant revokedAt,
        boolean activeSanction) {}

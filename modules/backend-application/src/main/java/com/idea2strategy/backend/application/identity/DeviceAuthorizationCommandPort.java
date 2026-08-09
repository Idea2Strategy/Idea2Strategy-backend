package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.UUID;

public interface DeviceAuthorizationCommandPort {
    UUID create(
            String deviceCodeDigest,
            String userCodeDigest,
            short digestKeyVersion,
            String clientLabel,
            short pollIntervalSeconds,
            Instant requestedAt,
            Instant expiresAt);

    /** Returns false when no unexpired pending request carries that user code. */
    boolean approve(String userCodeDigest, UUID accountId, Instant at);

    boolean deny(String userCodeDigest, Instant at);

    /** Atomically moves an approved request to consumed; every other state answers without a token. */
    DeviceAuthorizationOutcome consume(String deviceCodeDigest, Instant at);
}

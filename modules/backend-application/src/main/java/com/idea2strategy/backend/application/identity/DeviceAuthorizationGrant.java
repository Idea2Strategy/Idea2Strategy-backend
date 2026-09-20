package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.UUID;

/** Returned once, at request time. The codes are never readable again. */
public record DeviceAuthorizationGrant(
        UUID id, String deviceCode, String userCode, Instant expiresAt, short pollIntervalSeconds) {}

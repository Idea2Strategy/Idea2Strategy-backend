package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.UUID;

public record SessionView(
        UUID sessionId,
        String deviceLabel,
        Instant issuedAt,
        Instant lastSeenAt,
        Instant expiresAt,
        boolean current) {}

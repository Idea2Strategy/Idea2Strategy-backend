package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.UUID;

public record PasswordResetConsumption(
        String tokenDigest, PasswordHash passwordHash, UUID correlationId, Instant consumedAt) {}

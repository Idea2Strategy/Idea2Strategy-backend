package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.UUID;

public record RecoveryCodeConsumption(
        UUID accountId, String codeDigest, PasswordHash passwordHash, UUID correlationId, Instant consumedAt) {}

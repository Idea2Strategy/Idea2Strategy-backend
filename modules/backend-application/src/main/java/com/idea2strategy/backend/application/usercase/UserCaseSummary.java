package com.idea2strategy.backend.application.usercase;

import java.time.Instant;
import java.util.UUID;

public record UserCaseSummary(
        UUID id,
        UserCaseType type,
        UserCaseStatus status,
        String subject,
        Instant createdAt,
        Instant updatedAt) {}

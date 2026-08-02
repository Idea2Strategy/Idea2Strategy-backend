package com.idea2strategy.backend.application.accountretention;

import java.util.UUID;

public record RetentionCandidate(UUID obligationId, UUID accountId, String dataCategory) {}

package com.idea2strategy.backend.application.accountclosure;

import java.time.Instant;
import java.util.UUID;

public record RetentionObligation(
        UUID id, UUID accountId, String dataCategory, RetentionDisposition disposition, Instant retainUntil) {}

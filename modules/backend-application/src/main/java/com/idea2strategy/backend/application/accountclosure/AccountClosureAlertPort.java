package com.idea2strategy.backend.application.accountclosure;

import java.time.Instant;
import java.util.UUID;

public interface AccountClosureAlertPort {
    void raise(UUID accountId, UUID correlationId, String code, String evidence, Instant occurredAt);
}

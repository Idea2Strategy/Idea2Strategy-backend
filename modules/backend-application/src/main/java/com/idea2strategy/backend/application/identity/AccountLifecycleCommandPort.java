package com.idea2strategy.backend.application.identity;

import java.util.UUID;

public interface AccountLifecycleCommandPort {
    AccountLifecycleResult executeAtomically(
            UUID accountId,
            AccountLifecycleCommandType commandType,
            String idempotencyKey,
            String requestHash,
            UUID correlationId,
            AccountLifecycleDecision decision);
}

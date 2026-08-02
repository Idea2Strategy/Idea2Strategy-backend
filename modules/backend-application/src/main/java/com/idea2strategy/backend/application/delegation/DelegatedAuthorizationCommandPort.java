package com.idea2strategy.backend.application.delegation;

import java.time.Instant;

public interface DelegatedAuthorizationCommandPort {
    /**
     * Resolves the idempotency receipt and commits the decision in one transaction. Implementations must invoke
     * {@code decision} only for a receipt miss, at most once, and return {@code newlyApplied=false} for replays.
     * The command and decision output intentionally contain no raw credential.
     */
    DelegatedAuthorizationExecution executeAtomically(
            DelegatedAuthorizationCommand command,
            Instant at,
            DelegatedAuthorizationDecision decision);
}

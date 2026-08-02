package com.idea2strategy.backend.application.delegation;

import java.time.Instant;

public interface DelegatedStrategyDerivationCommandPort {
    /**
     * Atomically verifies the expected authorization version, resolves the idempotency key/request hash, creates
     * the Strategy, and appends its provenance. A same-key/different-hash request must fail as a conflict.
     */
    DelegatedStrategyDerivationResult executeAtomically(
            DelegatedStrategyDerivationCommand command,
            Instant at,
            DelegatedStrategyDerivationDecision decision);
}

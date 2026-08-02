package com.idea2strategy.backend.application.accountclosure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AccountClosureStore {
    List<AccountClosureCandidate> findClosingCandidates(int limit);

    void recordReadiness(UUID accountId, UUID correlationId, ClosureReadiness readiness);

    boolean closeIfReady(
            AccountClosureCandidate candidate,
            UUID correlationId,
            String idempotencyKey,
            Instant closedAt);
}

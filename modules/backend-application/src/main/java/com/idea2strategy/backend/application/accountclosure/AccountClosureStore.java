package com.idea2strategy.backend.application.accountclosure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AccountClosureStore {
    List<AccountClosureCandidate> findClosingCandidates(int limit);

    long beginAttempt(AccountClosureCandidate candidate, UUID correlationId, Instant startedAt);

    void recordReadiness(UUID accountId, UUID correlationId, long generation, ClosureReadiness readiness);

    boolean closeIfReady(
            AccountClosureCandidate candidate,
            UUID correlationId,
            long generation,
            String idempotencyKey,
            Instant closedAt);
}

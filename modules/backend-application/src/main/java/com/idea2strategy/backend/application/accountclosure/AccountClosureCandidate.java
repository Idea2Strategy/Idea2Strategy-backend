package com.idea2strategy.backend.application.accountclosure;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccountClosureCandidate(UUID accountId, Instant cancellationDeadlineAt, long lifecycleVersion) {
    public AccountClosureCandidate {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(cancellationDeadlineAt, "cancellationDeadlineAt");
    }
}

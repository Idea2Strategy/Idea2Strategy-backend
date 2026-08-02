package com.idea2strategy.backend.application.competition;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class PrivateContinuationTransitionService {
    private final PrivateContinuationTransitionPort port;
    private final Clock clock;

    public PrivateContinuationTransitionService(PrivateContinuationTransitionPort port, Clock clock) {
        this.port = Objects.requireNonNull(port, "port");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PrivateContinuationTransitionReport run(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Instant observedAt = clock.instant();
        int applied = 0;
        while (applied < limit
                && port.transitionNext(observedAt) == PrivateContinuationTransitionDecision.APPLIED) {
            applied++;
        }
        return new PrivateContinuationTransitionReport(observedAt, applied);
    }
}

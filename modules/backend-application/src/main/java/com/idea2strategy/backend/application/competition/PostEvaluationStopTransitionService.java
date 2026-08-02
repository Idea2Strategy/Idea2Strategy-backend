package com.idea2strategy.backend.application.competition;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class PostEvaluationStopTransitionService {
    private final PostEvaluationStopTransitionPort port;
    private final Clock clock;

    public PostEvaluationStopTransitionService(PostEvaluationStopTransitionPort port, Clock clock) {
        this.port = Objects.requireNonNull(port, "port");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PostEvaluationStopTransitionReport run(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Instant observedAt = clock.instant();
        int applied = 0;
        while (applied < limit
                && port.transitionNext(observedAt) == PostEvaluationStopTransitionDecision.APPLIED) {
            applied++;
        }
        return new PostEvaluationStopTransitionReport(observedAt, applied);
    }
}

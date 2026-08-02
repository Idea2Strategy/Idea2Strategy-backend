package com.idea2strategy.backend.application.competition;

import java.time.Instant;

public interface PrivateContinuationTransitionPort {
    PrivateContinuationTransitionDecision transitionNext(Instant observedAt);
}

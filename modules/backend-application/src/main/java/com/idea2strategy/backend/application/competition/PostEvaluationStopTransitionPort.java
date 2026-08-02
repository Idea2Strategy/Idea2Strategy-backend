package com.idea2strategy.backend.application.competition;

import java.time.Instant;

public interface PostEvaluationStopTransitionPort {
    PostEvaluationStopTransitionDecision transitionNext(Instant observedAt);
}

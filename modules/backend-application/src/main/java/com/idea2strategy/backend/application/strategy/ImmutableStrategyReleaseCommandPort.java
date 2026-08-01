package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease;
import java.util.UUID;

public interface ImmutableStrategyReleaseCommandPort {
    ImmutableStrategyRelease saveOnce(
            ImmutableStrategyRelease release,
            UUID validationRunId,
            long validatedEditSequence,
            String validatedSemanticHash);
}

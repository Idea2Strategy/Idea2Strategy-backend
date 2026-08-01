package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease;
import java.time.Instant;
import java.util.UUID;

public interface RoomStrategyBotProvisioningPort {
    UUID provision(
            ImmutableStrategyRelease release,
            UUID validationRunId,
            long validatedEditSequence,
            String validatedSemanticHash,
            Instant executionEligibleFrom);
}

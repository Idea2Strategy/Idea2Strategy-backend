package com.idea2strategy.backend.application.competition;

import java.time.Instant;

public interface VirtualLiquidationResultPort {
    VirtualLiquidationWriteDecision save(
            VirtualLiquidationContext context,
            VirtualLiquidationPerformance performance,
            Instant finalizedAt);
}

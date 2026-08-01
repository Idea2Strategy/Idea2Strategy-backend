package com.idea2strategy.backend.application.strategy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface StrategyLibraryQueryPort {
    List<StrategyLibraryItem> findVisible(
            UUID ownerAccountId,
            Instant snapshotAt,
            StrategyLibraryPosition after,
            int limit);
}

package com.idea2strategy.backend.application.competition;

import java.time.Instant;

@FunctionalInterface
public interface RoomExecutionPolicyCatalogQueryPort {
    RoomExecutionPolicyCatalog findSelectableAt(Instant at);
}

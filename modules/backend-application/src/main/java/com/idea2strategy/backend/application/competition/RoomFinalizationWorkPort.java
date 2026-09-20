package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomFinalizationWorkPort {
    List<UUID> findDueRoomIds(Instant observedAt, int limit);

    List<VirtualLiquidationRequest> findPendingLiquidations(UUID roomId);

    void markEvaluationCompleted(VirtualLiquidationRequest request, Instant completedAt);

    Optional<RoomFinalizationSource> loadReadyResult(UUID roomId);
}

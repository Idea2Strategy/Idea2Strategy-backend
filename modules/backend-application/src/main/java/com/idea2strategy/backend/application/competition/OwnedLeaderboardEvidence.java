package com.idea2strategy.backend.application.competition;

import java.util.UUID;

public record OwnedLeaderboardEvidence(
        UUID botId,
        UUID participationId,
        UUID performanceSnapshotId,
        UUID backtestAggregateResultId,
        String eligibilityReasonCode) {}

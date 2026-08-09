package com.idea2strategy.backend.application.competition;

import java.time.Instant;

public interface BacktestCompetitionSettlementPort {
    BacktestCompetitionSettlementReport settleEligible(Instant observedAt, int limit);
}

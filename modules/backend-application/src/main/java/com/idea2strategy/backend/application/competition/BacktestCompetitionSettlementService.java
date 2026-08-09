package com.idea2strategy.backend.application.competition;

import java.time.Clock;
import java.util.Objects;

public final class BacktestCompetitionSettlementService {
    private final BacktestCompetitionSettlementPort port;
    private final Clock clock;

    public BacktestCompetitionSettlementService(BacktestCompetitionSettlementPort port, Clock clock) {
        this.port = Objects.requireNonNull(port, "port");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BacktestCompetitionSettlementReport run(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return port.settleEligible(clock.instant(), limit);
    }
}

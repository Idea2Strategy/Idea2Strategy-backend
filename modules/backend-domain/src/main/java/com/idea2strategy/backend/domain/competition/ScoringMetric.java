package com.idea2strategy.backend.domain.competition;

public enum ScoringMetric {
    TOTAL_RETURN(ScoringDirection.HIGHER_IS_BETTER),
    SHARPE_RATIO(ScoringDirection.HIGHER_IS_BETTER),
    MAX_DRAWDOWN(ScoringDirection.LOWER_IS_BETTER);

    private final ScoringDirection direction;

    ScoringMetric(ScoringDirection direction) {
        this.direction = direction;
    }

    public ScoringDirection direction() {
        return direction;
    }
}

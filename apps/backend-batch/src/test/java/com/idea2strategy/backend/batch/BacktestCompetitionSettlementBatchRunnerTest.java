package com.idea2strategy.backend.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.competition.BacktestCompetitionSettlementReport;
import com.idea2strategy.backend.application.competition.BacktestCompetitionSettlementService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

class BacktestCompetitionSettlementBatchRunnerTest {
    private static final Instant NOW = Instant.parse("2026-08-10T04:00:00Z");

    @Test
    void productionSchedulerRunsByDefault() {
        ConditionalOnProperty gate = BacktestCompetitionSettlementBatchConfiguration.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(gate.name())
                .containsExactly("idea2strategy.batch.backtest-competition-settlement.enabled");
        assertThat(gate.havingValue()).isEqualTo("true");
        assertThat(gate.matchIfMissing()).isTrue();
    }

    @Test
    void forwardsTheBoundedBatchSizeToSettlement() {
        var observedLimit = new AtomicInteger();
        var service = new BacktestCompetitionSettlementService((observedAt, limit) -> {
            observedLimit.set(limit);
            return new BacktestCompetitionSettlementReport(observedAt, 0, 0, 0, 0);
        }, Clock.fixed(NOW, ZoneOffset.UTC));

        new BacktestCompetitionSettlementBatchRunner(service, 37).run();

        assertThat(observedLimit).hasValue(37);
    }
}

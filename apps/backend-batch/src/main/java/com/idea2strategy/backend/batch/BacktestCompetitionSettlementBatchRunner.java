package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.competition.BacktestCompetitionSettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class BacktestCompetitionSettlementBatchRunner {
    private static final Logger log = LoggerFactory.getLogger(BacktestCompetitionSettlementBatchRunner.class);
    private final BacktestCompetitionSettlementService service;
    private final int batchSize;

    BacktestCompetitionSettlementBatchRunner(
            BacktestCompetitionSettlementService service, int batchSize) {
        this.service = service;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${idea2strategy.batch.backtest-competition-settlement.fixed-delay:PT10S}")
    void run() {
        var report = service.run(batchSize);
        log.info(
                "Backtest competition settlement batch completed: participantsCompleted={}, "
                        + "participantsFailed={}, publishedSnapshots={}, finalSnapshots={}, observedAt={}",
                report.participantsCompleted(),
                report.participantsFailed(),
                report.publishedSnapshots(),
                report.finalSnapshots(),
                report.observedAt());
    }
}

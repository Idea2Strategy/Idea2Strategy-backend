package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.competition.PrivateContinuationTransitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class PrivateContinuationTransitionBatchRunner {
    private static final Logger log = LoggerFactory.getLogger(PrivateContinuationTransitionBatchRunner.class);
    private final PrivateContinuationTransitionService service;
    private final int batchSize;

    PrivateContinuationTransitionBatchRunner(PrivateContinuationTransitionService service, int batchSize) {
        this.service = service;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${idea2strategy.batch.private-continuation-transition.fixed-delay:PT10S}")
    void run() {
        var report = service.run(batchSize);
        log.info(
                "Private continuation transition batch completed: transitionsApplied={}, observedAt={}",
                report.transitionsApplied(),
                report.observedAt());
    }
}

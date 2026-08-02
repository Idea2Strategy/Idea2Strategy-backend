package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.competition.RoomEvaluationStartService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class RoomEvaluationStartBatchRunner {
    private static final Logger log = LoggerFactory.getLogger(RoomEvaluationStartBatchRunner.class);
    private final RoomEvaluationStartService service;
    private final int batchSize;

    RoomEvaluationStartBatchRunner(RoomEvaluationStartService service, int batchSize) {
        this.service = service;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${idea2strategy.batch.room-evaluation-start.fixed-delay:PT10S}")
    void run() {
        var report = service.run(batchSize);
        log.info(
                "Room evaluation start batch completed: participantsStarted={}, observedAt={}",
                report.participantsStarted(),
                report.observedAt());
    }
}

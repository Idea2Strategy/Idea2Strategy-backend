package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.competition.RoomFinalizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class RoomFinalizationBatchRunner {
    private static final Logger log = LoggerFactory.getLogger(RoomFinalizationBatchRunner.class);
    private final RoomFinalizationService service;
    private final int batchSize;

    RoomFinalizationBatchRunner(RoomFinalizationService service, int batchSize) {
        this.service = service;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${idea2strategy.batch.room-finalization.fixed-delay:PT10S}")
    void run() {
        var report = service.run(batchSize);
        log.info(
                "Room finalization batch completed: roomsAttempted={}, roomsFinalized={}, "
                        + "participationsFinalized={}, failures={}, observedAt={}",
                report.roomsAttempted(), report.roomsFinalized(), report.participationsFinalized(),
                report.failures().size(), report.observedAt());
        report.failures().forEach(failure -> log.warn(
                "Room finalization remains retryable: roomId={}, reason={}",
                failure.roomId(), failure.reason()));
    }
}

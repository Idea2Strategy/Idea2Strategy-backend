package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.competition.RoomScheduleTransitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class RoomScheduleTransitionBatchRunner {
    private static final Logger log = LoggerFactory.getLogger(RoomScheduleTransitionBatchRunner.class);
    private final RoomScheduleTransitionService service;
    private final int batchSize;

    RoomScheduleTransitionBatchRunner(RoomScheduleTransitionService service, int batchSize) {
        this.service = service;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${idea2strategy.batch.room-schedule-transition.fixed-delay:PT10S}")
    void run() {
        var report = service.run(batchSize);
        log.info(
                "Room schedule transition batch completed: roomsAdvanced={}, transitionsApplied={}, observedAt={}",
                report.roomsAdvanced(),
                report.transitionsApplied(),
                report.observedAt());
    }
}

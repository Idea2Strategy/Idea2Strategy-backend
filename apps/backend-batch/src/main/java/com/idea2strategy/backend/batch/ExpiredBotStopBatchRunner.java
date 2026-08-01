package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.botcontrol.ExpiredBotStopBatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class ExpiredBotStopBatchRunner {
    private static final Logger log = LoggerFactory.getLogger(ExpiredBotStopBatchRunner.class);

    private final ExpiredBotStopBatchService service;
    private final int batchSize;

    ExpiredBotStopBatchRunner(ExpiredBotStopBatchService service, int batchSize) {
        this.service = service;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${idea2strategy.batch.expired-bot-stop.fixed-delay:PT1M}")
    void run() {
        var report = service.run(batchSize);
        log.info(
                "Expired bot stop batch completed: scanned={}, stopsStarted={}, skipped={}",
                report.scanned(),
                report.stopsStarted(),
                report.skipped());
    }
}

package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.identity.PendingRegistrationCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class PendingRegistrationCleanupBatchRunner {
    private static final Logger log = LoggerFactory.getLogger(PendingRegistrationCleanupBatchRunner.class);
    private final PendingRegistrationCleanupService cleanup;
    private final int batchSize;

    PendingRegistrationCleanupBatchRunner(PendingRegistrationCleanupService cleanup, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.cleanup = cleanup;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${idea2strategy.batch.pending-registration-cleanup.fixed-delay:PT1H}")
    void run() {
        int purged = cleanup.purgeExpired(batchSize);
        log.info("Pending registration cleanup batch completed: purged={}", purged);
    }
}

package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.accountretention.AccountRetentionCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class AccountRetentionBatchRunner {
    private static final Logger log = LoggerFactory.getLogger(AccountRetentionBatchRunner.class);
    private final AccountRetentionCoordinator coordinator;
    private final int batchSize;

    AccountRetentionBatchRunner(AccountRetentionCoordinator coordinator, int batchSize) {
        this.coordinator = coordinator;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${idea2strategy.batch.account-retention.fixed-delay:PT5M}")
    void run() {
        var result = coordinator.run(batchSize);
        log.info("Account retention batch completed: inspected={}, completed={}, held={}, failed={}",
                result.inspected(), result.completed(), result.held(), result.failed());
    }
}

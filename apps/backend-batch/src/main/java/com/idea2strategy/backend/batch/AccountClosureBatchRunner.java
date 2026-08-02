package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.accountclosure.AccountClosureCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class AccountClosureBatchRunner {
    private static final Logger log = LoggerFactory.getLogger(AccountClosureBatchRunner.class);
    private final AccountClosureCoordinator coordinator;
    private final int batchSize;

    AccountClosureBatchRunner(
            AccountClosureCoordinator coordinator, int batchSize) {
        this.coordinator = coordinator;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${idea2strategy.batch.account-closure.fixed-delay:PT1M}")
    void run() {
        var result = coordinator.run(batchSize);
        log.info("Account closure batch completed: inspected={}, closed={}, blocked={}",
                result.inspected(), result.closed(), result.blocked());
    }
}

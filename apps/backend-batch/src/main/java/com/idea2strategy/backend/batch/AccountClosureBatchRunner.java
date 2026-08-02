package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.accountclosure.AccountClosureCoordinator;
import com.idea2strategy.backend.application.accountclosure.IdentifierQuarantineReleaseWorker;
import com.idea2strategy.backend.application.accountclosure.RetentionDispositionWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class AccountClosureBatchRunner {
    private static final Logger log = LoggerFactory.getLogger(AccountClosureBatchRunner.class);
    private final AccountClosureCoordinator coordinator;
    private final RetentionDispositionWorker retention;
    private final IdentifierQuarantineReleaseWorker quarantine;
    private final int batchSize;

    AccountClosureBatchRunner(
            AccountClosureCoordinator coordinator,
            RetentionDispositionWorker retention,
            IdentifierQuarantineReleaseWorker quarantine,
            int batchSize) {
        this.coordinator = coordinator;
        this.retention = retention;
        this.quarantine = quarantine;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${idea2strategy.batch.account-closure.fixed-delay:PT1M}")
    void run() {
        var result = coordinator.run(batchSize);
        int obligations = retention.run(batchSize);
        int released = quarantine.run(batchSize);
        log.info("Account closure batch completed: inspected={}, closed={}, blocked={}, obligations={}, identifiersReleased={}",
                result.inspected(), result.closed(), result.blocked(), obligations, released);
    }
}

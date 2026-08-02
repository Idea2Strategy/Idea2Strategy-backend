package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.identity.AccountLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class AccountDormancyBatchRunner {
    private static final Logger log = LoggerFactory.getLogger(AccountDormancyBatchRunner.class);

    private final AccountLifecycleService lifecycle;
    private final int batchSize;

    AccountDormancyBatchRunner(AccountLifecycleService lifecycle, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.lifecycle = lifecycle;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${idea2strategy.batch.account-dormancy.fixed-delay:PT1H}")
    void run() {
        int transitioned = lifecycle.markDormantCandidates(batchSize).size();
        log.info("Account dormancy batch completed: transitioned={}", transitioned);
    }
}

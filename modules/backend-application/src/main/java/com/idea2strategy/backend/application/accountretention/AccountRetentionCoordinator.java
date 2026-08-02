package com.idea2strategy.backend.application.accountretention;

import java.time.Clock;
import java.util.UUID;

public final class AccountRetentionCoordinator {
    private final RetentionExecutionStore store;
    private final Clock clock;

    public AccountRetentionCoordinator(RetentionExecutionStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public RetentionBatchResult run(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        var now = clock.instant();
        int completed = 0;
        int held = 0;
        int failed = 0;
        var accounts = store.findDueAccounts(limit, now);
        for (var accountId : accounts) {
            var correlationId = UUID.randomUUID();
            try {
                for (var result : store.executeAccount(accountId, correlationId, now)) {
                    switch (result) {
                        case COMPLETED -> completed++;
                        case HELD -> held++;
                        case SKIPPED -> { }
                    }
                }
            } catch (RuntimeException failure) {
                failed++;
                store.recordAccountFailure(accountId, correlationId, failureCode(failure), now);
            }
        }
        return new RetentionBatchResult(accounts.size(), completed, held, failed);
    }

    private static String failureCode(RuntimeException failure) {
        String simpleName = failure.getClass().getSimpleName().toUpperCase();
        return (simpleName.length() > 70 ? simpleName.substring(0, 70) : simpleName) + "_ERROR";
    }
}

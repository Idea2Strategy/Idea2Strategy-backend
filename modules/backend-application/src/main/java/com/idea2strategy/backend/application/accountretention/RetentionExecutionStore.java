package com.idea2strategy.backend.application.accountretention;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RetentionExecutionStore {
    List<UUID> findDueAccounts(int limit, Instant now);

    List<RetentionExecutionResult> executeAccount(
            UUID accountId, UUID correlationId, Instant now);

    void recordAccountFailure(UUID accountId, UUID correlationId, String failureCode, Instant now);
}

package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.UUID;

public record AccountLifecycleResult(
        UUID accountId,
        AccountLifecycleStatus status,
        long version,
        Instant withdrawalRequestedAt,
        Instant cancellationDeadlineAt,
        boolean applied) {
    public static AccountLifecycleResult applied(AccountLifecycleSnapshot snapshot) {
        return from(snapshot, true);
    }

    public static AccountLifecycleResult skipped(AccountLifecycleSnapshot snapshot) {
        return from(snapshot, false);
    }

    private static AccountLifecycleResult from(AccountLifecycleSnapshot snapshot, boolean applied) {
        return new AccountLifecycleResult(
                snapshot.accountId(),
                snapshot.status(),
                snapshot.version(),
                snapshot.withdrawalRequestedAt(),
                snapshot.cancellationDeadlineAt(),
                applied);
    }
}

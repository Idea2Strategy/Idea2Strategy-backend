package com.idea2strategy.backend.application.accountsanction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AccountSanctionExpiryPort {
    List<DueSanction> findDue(int limit);

    record DueSanction(UUID accountId, UUID sanctionId, Instant expiresAt, long aggregateVersion) {}
}

package com.idea2strategy.backend.application.accountclosure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface IdentifierQuarantinePort {
    record DueIdentifier(UUID quarantineId, UUID accountId, String identifierKind) {}
    List<DueIdentifier> findDueIdentifiers(int limit, Instant now);
    boolean hasReuseBlockingLegalHold(UUID accountId);
    boolean releaseBindingAndQuarantine(DueIdentifier identifier, Instant releasedAt);
}

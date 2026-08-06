package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionQueryPort {
    Optional<StoredSession> findByTokenDigest(String tokenDigest);

    Optional<StoredSession> findById(UUID sessionId);

    List<ActiveSession> findActiveByAccountId(UUID accountId, Instant now);
}

package com.idea2strategy.backend.application.notification;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class NotificationQueryService {
    private final NotificationQueryPort query;

    public NotificationQueryService(NotificationQueryPort query) {
        this.query = Objects.requireNonNull(query, "query");
    }

    public NotificationQueryPort.NotificationPage list(
            UUID accountId, Instant beforeCreatedAt, UUID beforeId, int limit) {
        Objects.requireNonNull(accountId, "accountId");
        if ((beforeCreatedAt == null) != (beforeId == null)) {
            throw new IllegalArgumentException("cursor timestamp and id must be supplied together");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return query.listOwned(accountId, beforeCreatedAt, beforeId, limit);
    }
}

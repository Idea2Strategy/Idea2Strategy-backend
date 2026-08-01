package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class StrategyLibraryQueryService {
    private static final int MAX_PAGE_SIZE = 100;

    private final StrategyLibraryQueryPort queryPort;
    private final CurrentPrincipal principal;
    private final Clock clock;

    public StrategyLibraryQueryService(
            StrategyLibraryQueryPort queryPort, CurrentPrincipal principal, Clock clock) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StrategyLibraryPage list(String cursor, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }

        Instant now = clock.instant();
        Instant snapshotAt = now;
        StrategyLibraryPosition after = null;
        if (cursor != null && !cursor.isBlank()) {
            StrategyLibraryCursor.Decoded decoded = StrategyLibraryCursor.decode(cursor);
            if (decoded.snapshotAt().isAfter(now)) {
                throw new IllegalArgumentException("cursor is invalid");
            }
            snapshotAt = decoded.snapshotAt();
            after = decoded.position();
        }

        List<StrategyLibraryItem> fetched = queryPort.findVisible(
                principal.accountId(), snapshotAt, after, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<StrategyLibraryItem> items = hasMore ? fetched.subList(0, limit) : fetched;
        String nextCursor = hasMore
                ? StrategyLibraryCursor.encode(snapshotAt, items.getLast().position())
                : null;
        return new StrategyLibraryPage(items, nextCursor, hasMore);
    }
}

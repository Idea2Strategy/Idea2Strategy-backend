package com.idea2strategy.backend.application.botcontrol;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public class ExpiredBotStopBatchService {
    private static final int MAX_BATCH_SIZE = 1_000;

    private final ExpiredBotStopQueryPort query;
    private final ExpiredBotStopCommandPort command;
    private final Clock clock;

    public ExpiredBotStopBatchService(
            ExpiredBotStopQueryPort query, ExpiredBotStopCommandPort command, Clock clock) {
        this.query = Objects.requireNonNull(query, "query");
        this.command = Objects.requireNonNull(command, "command");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ExpiredBotStopBatchReport run(int limit) {
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }

        Instant ranAt = clock.instant();
        var candidates = query.findExpired(ranAt, limit);
        int stopsStarted = 0;
        for (var candidate : candidates) {
            if (command.issueExpired(candidate, ranAt)) {
                stopsStarted++;
            }
        }
        return new ExpiredBotStopBatchReport(
                ranAt, candidates.size(), stopsStarted, candidates.size() - stopsStarted);
    }
}

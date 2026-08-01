package com.idea2strategy.backend.application.botcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExpiredBotStopBatchServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T09:00:00Z");

    @Test
    void startsStopsForExpiredCandidatesAndIgnoresConcurrentWinners() {
        var first = candidate("20000000-0000-4000-8000-000000000001");
        var second = candidate("20000000-0000-4000-8000-000000000002");
        var third = candidate("20000000-0000-4000-8000-000000000003");
        var queriedAt = new AtomicReference<Instant>();
        ExpiredBotStopQueryPort query = (expiredAt, limit) -> {
            queriedAt.set(expiredAt);
            assertThat(limit).isEqualTo(100);
            return List.of(first, second, third);
        };
        ExpiredBotStopCommandPort command = (candidate, requestedAt) -> !candidate.equals(second);
        var service = new ExpiredBotStopBatchService(query, command, fixedClock());

        ExpiredBotStopBatchReport report = service.run(100);

        assertThat(queriedAt).hasValue(NOW);
        assertThat(report).isEqualTo(new ExpiredBotStopBatchReport(NOW, 3, 2, 1));
    }

    @Test
    void rejectsUnsafeBatchLimits() {
        var service = new ExpiredBotStopBatchService(
                (expiredAt, limit) -> List.of(),
                (candidate, requestedAt) -> false,
                fixedClock());

        assertThatThrownBy(() -> service.run(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and 1000");
        assertThatThrownBy(() -> service.run(1001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and 1000");
    }

    private static ExpiredBotStopCandidate candidate(String botId) {
        return new ExpiredBotStopCandidate(
                UUID.fromString(botId),
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                NOW.minusSeconds(60));
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}

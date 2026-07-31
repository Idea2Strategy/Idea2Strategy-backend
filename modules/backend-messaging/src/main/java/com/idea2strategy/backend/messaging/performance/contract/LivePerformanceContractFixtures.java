package com.idea2strategy.backend.messaging.performance.contract;

import com.idea2strategy.backend.messaging.competition.contract.RoomContractFixtures;
import com.idea2strategy.backend.messaging.competition.contract.RoomScheduleFixture;
import java.time.Instant;
import java.util.UUID;

public final class LivePerformanceContractFixtures {

    private LivePerformanceContractFixtures() {
    }

    public static LivePerformanceInputFixture liveFillAt(RoomScheduleFixture schedule, Instant occurredAt) {
        return input(schedule, occurredAt, PerformanceInputSource.LIVE_TRADING, PerformanceEventType.FILL, 41L);
    }

    public static LivePerformanceInputFixture backtestResultAt(RoomScheduleFixture schedule, Instant occurredAt) {
        return input(schedule, occurredAt, PerformanceInputSource.BACKTEST, PerformanceEventType.BACKTEST_RESULT, 42L);
    }

    private static LivePerformanceInputFixture input(
        RoomScheduleFixture schedule,
        Instant occurredAt,
        PerformanceInputSource source,
        PerformanceEventType eventType,
        long sequence
    ) {
        UUID eventId = UUID.fromString("60000000-0000-4000-8000-%012d".formatted(sequence));
        return new LivePerformanceInputFixture(
            RoomContractFixtures.CONTRACT_VERSION,
            eventId,
            schedule.roomId(),
            RoomContractFixtures.EVALUATION_SEGMENT_ID,
            "bot-orchid-07",
            schedule.scheduleVersion(),
            source,
            eventType,
            sequence,
            occurredAt,
            "sha256:" + (sequence == 41L ? "a" : "b").repeat(64)
        );
    }
}

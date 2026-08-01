package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.domain.competition.CompetitionType;
import com.idea2strategy.backend.domain.competition.RoomStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RoomSubmissionScheduleTest {
    private static final Instant OPENS = Instant.parse("2026-08-02T01:00:00Z");
    private static final Instant EVALUATES = Instant.parse("2026-08-02T02:00:00Z");
    private static final Instant CLOSES = Instant.parse("2026-08-02T03:00:00Z");

    @Test
    void livePaperWaitsBeforeEvaluationAndRejectsAtEvaluationStart() {
        var schedule = schedule(CompetitionType.LIVE_PAPER, RoomStatus.RECRUITING);
        assertThat(schedule.timingAt(OPENS)).contains(RoomSubmissionTiming.WAIT_UNTIL_EVALUATION);
        assertThat(schedule.timingAt(EVALUATES)).isEmpty();
    }

    @Test
    void backtestStartsImmediatelyDuringEvaluationUntilTheExclusiveClose() {
        var schedule = schedule(CompetitionType.BACKTEST, RoomStatus.EVALUATING);
        assertThat(schedule.timingAt(EVALUATES)).contains(RoomSubmissionTiming.START_IMMEDIATELY);
        assertThat(schedule.timingAt(CLOSES.minusNanos(1))).contains(RoomSubmissionTiming.START_IMMEDIATELY);
        assertThat(schedule.timingAt(CLOSES)).isEmpty();
    }

    private static RoomSubmissionSchedule schedule(CompetitionType type, RoomStatus status) {
        return new RoomSubmissionSchedule(type, status, OPENS, EVALUATES, CLOSES);
    }
}

package com.idea2strategy.backend.messaging.performance.contract;

import com.idea2strategy.backend.messaging.competition.contract.RoomCompetitionType;
import com.idea2strategy.backend.messaging.competition.contract.RoomScheduleFixture;
import java.util.Objects;
import java.util.UUID;

public final class LivePerformanceInputValidator {

    public LivePerformanceInputDecision validate(
        RoomScheduleFixture schedule,
        UUID expectedEvaluationSegmentId,
        LivePerformanceInputFixture input
    ) {
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(expectedEvaluationSegmentId, "expectedEvaluationSegmentId");
        Objects.requireNonNull(input, "input");

        if (schedule.competitionType() != RoomCompetitionType.LIVE_PAPER) {
            return LivePerformanceInputDecision.BACKTEST_SOURCE_NOT_ALLOWED;
        }
        if (!schedule.roomId().equals(input.roomId())) {
            return LivePerformanceInputDecision.ROOM_MISMATCH;
        }
        if (!expectedEvaluationSegmentId.equals(input.evaluationSegmentId())) {
            return LivePerformanceInputDecision.SEGMENT_MISMATCH;
        }
        if (!schedule.scheduleVersion().equals(input.scheduleVersion())) {
            return LivePerformanceInputDecision.SCHEDULE_VERSION_MISMATCH;
        }
        if (input.source() == PerformanceInputSource.BACKTEST) {
            return LivePerformanceInputDecision.BACKTEST_SOURCE_NOT_ALLOWED;
        }
        if (input.occurredAt().isBefore(schedule.evaluationStartsAt())) {
            return LivePerformanceInputDecision.BEFORE_EVALUATION_START;
        }
        if (!input.occurredAt().isBefore(schedule.evaluationEndsAt())) {
            return LivePerformanceInputDecision.AT_OR_AFTER_EVALUATION_END;
        }
        return LivePerformanceInputDecision.ACCEPTED;
    }
}

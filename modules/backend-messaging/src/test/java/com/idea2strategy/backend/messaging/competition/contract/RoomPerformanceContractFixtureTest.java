package com.idea2strategy.backend.messaging.competition.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.backend.messaging.performance.contract.LivePerformanceContractFixtures;
import com.idea2strategy.backend.messaging.performance.contract.LivePerformanceInputDecision;
import com.idea2strategy.backend.messaging.performance.contract.LivePerformanceInputFixture;
import com.idea2strategy.backend.messaging.performance.contract.LivePerformanceInputValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RoomPerformanceContractFixtureTest {

    private final LivePerformanceInputValidator validator = new LivePerformanceInputValidator();

    @Test
    void rejectsFillAfterTheLockedEvaluationEnd() {
        RoomScheduleFixture schedule = RoomContractFixtures.publicLiveRoomSchedule();
        LivePerformanceInputFixture input = LivePerformanceContractFixtures.liveFillAt(
            schedule,
            schedule.evaluationEndsAt().plusSeconds(1)
        );

        assertEquals(
            LivePerformanceInputDecision.AT_OR_AFTER_EVALUATION_END,
            validator.validate(schedule, input.evaluationSegmentId(), input)
        );
    }

    @Test
    void exposesVersionedSchedulesForPublicPrivateAndOfficialRooms() {
        List<RoomScheduleFixture> schedules = RoomContractFixtures.roomSchedules();

        assertEquals(3, schedules.size());
        assertEquals(
            Set.of(RoomAccessType.PUBLIC, RoomAccessType.PRIVATE),
            schedules.stream().map(RoomScheduleFixture::accessType).collect(Collectors.toSet())
        );
        assertTrue(schedules.stream().allMatch(schedule -> !schedule.scheduleVersion().isBlank()));
        assertTrue(schedules.stream().allMatch(schedule ->
            schedule.contractVersion().equals(RoomContractFixtures.CONTRACT_VERSION)
        ));
        assertTrue(schedules.stream().anyMatch(schedule ->
            schedule.organizerType() == RoomOrganizerType.PLATFORM
                && schedule.competitionType() == RoomCompetitionType.BACKTEST
        ));
    }

    @Test
    void rejectsAnInvalidScheduleBoundaryOrder() {
        RoomScheduleFixture valid = RoomContractFixtures.publicLiveRoomSchedule();

        assertThrows(IllegalArgumentException.class, () -> new RoomScheduleFixture(
            valid.contractVersion(),
            valid.roomId(),
            valid.scheduleVersion(),
            valid.competitionType(),
            valid.organizerType(),
            valid.accessType(),
            valid.recruitmentOpensAt(),
            valid.participationOpensAt(),
            valid.evaluationStartsAt(),
            valid.participationClosesAt(),
            valid.evaluationStartsAt(),
            valid.finalizationDeadlineAt(),
            valid.timeZone()
        ));
    }

    @Test
    void suppliesEveryEvaluationAndPostRoomCommand() {
        RoomScheduleFixture schedule = RoomContractFixtures.publicLiveRoomSchedule();
        List<RoomEvaluationCommandFixture> commands = RoomContractFixtures.evaluationCommands(schedule);

        assertEquals(
            Set.of(
                RoomEvaluationCommandType.INITIALIZE_EVALUATION,
                RoomEvaluationCommandType.START_EVALUATION,
                RoomEvaluationCommandType.END_EVALUATION,
                RoomEvaluationCommandType.CONTINUE_AS_PRIVATE_BOT,
                RoomEvaluationCommandType.STOP_BOT
            ),
            commands.stream().map(RoomEvaluationCommandFixture::type).collect(Collectors.toSet())
        );
        assertTrue(commands.stream().allMatch(command -> command.roomId().equals(schedule.roomId())));
        assertTrue(commands.stream().allMatch(command -> command.scheduleVersion().equals(schedule.scheduleVersion())));
        assertTrue(commands.stream().allMatch(command -> command.evaluationStartsAt().equals(schedule.evaluationStartsAt())));
        assertTrue(commands.stream().allMatch(command -> command.evaluationEndsAt().equals(schedule.evaluationEndsAt())));
        assertTrue(commands.stream().allMatch(command -> !command.idempotencyKey().isBlank()));
    }

    @Test
    void rejectsInvalidEvaluationCommandBoundaries() {
        RoomScheduleFixture schedule = RoomContractFixtures.publicLiveRoomSchedule();
        RoomEvaluationCommandFixture valid = RoomContractFixtures.evaluationCommands(schedule).get(1);

        assertThrows(IllegalArgumentException.class, () -> new RoomEvaluationCommandFixture(
            valid.contractVersion(), valid.commandId(), valid.type(), valid.roomId(),
            valid.participationId(), valid.botId(), valid.evaluationSegmentId(), valid.scheduleVersion(),
            valid.evaluationStartsAt(), valid.evaluationStartsAt(), valid.effectiveAt(), valid.idempotencyKey()
        ));
        assertThrows(IllegalArgumentException.class, () -> new RoomEvaluationCommandFixture(
            valid.contractVersion(), valid.commandId(), valid.type(), valid.roomId(),
            valid.participationId(), valid.botId(), valid.evaluationSegmentId(), valid.scheduleVersion(),
            valid.evaluationStartsAt(), valid.evaluationEndsAt(),
            valid.evaluationStartsAt().minusSeconds(1), valid.idempotencyKey()
        ));
    }

    @Test
    void rejectsInputsBeforeTheSegmentAndFromBacktests() {
        RoomScheduleFixture schedule = RoomContractFixtures.publicLiveRoomSchedule();
        Instant beforeStart = schedule.evaluationStartsAt().minusSeconds(1);

        LivePerformanceInputFixture earlyInput = LivePerformanceContractFixtures.liveFillAt(schedule, beforeStart);
        LivePerformanceInputFixture backtestInput = LivePerformanceContractFixtures.backtestResultAt(
            schedule,
            schedule.evaluationStartsAt()
        );

        assertEquals(
            LivePerformanceInputDecision.BEFORE_EVALUATION_START,
            validator.validate(schedule, earlyInput.evaluationSegmentId(), earlyInput)
        );
        assertEquals(
            LivePerformanceInputDecision.BACKTEST_SOURCE_NOT_ALLOWED,
            validator.validate(schedule, backtestInput.evaluationSegmentId(), backtestInput)
        );
    }

    @Test
    void acceptsTheStartBoundaryAndRejectsTheEndBoundary() {
        RoomScheduleFixture schedule = RoomContractFixtures.publicLiveRoomSchedule();
        LivePerformanceInputFixture atStart = LivePerformanceContractFixtures.liveFillAt(
            schedule,
            schedule.evaluationStartsAt()
        );
        LivePerformanceInputFixture atEnd = LivePerformanceContractFixtures.liveFillAt(
            schedule,
            schedule.evaluationEndsAt()
        );

        assertEquals(
            LivePerformanceInputDecision.ACCEPTED,
            validator.validate(schedule, atStart.evaluationSegmentId(), atStart)
        );
        assertEquals(
            LivePerformanceInputDecision.AT_OR_AFTER_EVALUATION_END,
            validator.validate(schedule, atEnd.evaluationSegmentId(), atEnd)
        );
    }

    @Test
    void exposesOnlyAnonymousBotIdentityOnTheWire() {
        RoomScheduleFixture schedule = RoomContractFixtures.publicLiveRoomSchedule();
        Map<String, Object> wire = LivePerformanceContractFixtures.liveFillAt(
            schedule,
            schedule.evaluationStartsAt().plus(Duration.ofMinutes(5))
        ).toWireMap();

        assertEquals("bot-orchid-07", wire.get("anonymousBotId"));
        assertFalse(wire.keySet().stream().map(key -> key.toLowerCase(Locale.ROOT)).anyMatch(key ->
            key.contains("user") || key.contains("account") || key.contains("owner")
        ));
        assertThrows(UnsupportedOperationException.class, () -> wire.put("ownerAccountId", "forbidden"));
    }
}

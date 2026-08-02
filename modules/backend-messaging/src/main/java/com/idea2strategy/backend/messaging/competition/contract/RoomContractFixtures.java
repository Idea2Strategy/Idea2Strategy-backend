package com.idea2strategy.backend.messaging.competition.contract;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public final class RoomContractFixtures {

    public static final String CONTRACT_VERSION = "room-performance.v1";
    public static final UUID PUBLIC_LIVE_ROOM_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    public static final UUID PRIVATE_LIVE_ROOM_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    public static final UUID OFFICIAL_BACKTEST_ROOM_ID = UUID.fromString("10000000-0000-4000-8000-000000000003");
    public static final UUID PARTICIPATION_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    public static final UUID BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    public static final UUID EVALUATION_SEGMENT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");

    private static final String SCHEDULE_VERSION = "room-schedule.v1";

    private RoomContractFixtures() {
    }

    public static List<RoomScheduleFixture> roomSchedules() {
        return List.of(publicLiveRoomSchedule(), privateLiveRoomSchedule(), officialBacktestRoomSchedule());
    }

    public static RoomScheduleFixture publicLiveRoomSchedule() {
        return liveRoomSchedule(PUBLIC_LIVE_ROOM_ID, RoomAccessType.PUBLIC, "2026-08-01T00:00:00Z");
    }

    public static RoomScheduleFixture privateLiveRoomSchedule() {
        return liveRoomSchedule(PRIVATE_LIVE_ROOM_ID, RoomAccessType.PRIVATE, "2026-09-01T00:00:00Z");
    }

    public static RoomScheduleFixture officialBacktestRoomSchedule() {
        return new RoomScheduleFixture(
            CONTRACT_VERSION,
            OFFICIAL_BACKTEST_ROOM_ID,
            SCHEDULE_VERSION,
            RoomCompetitionType.BACKTEST,
            RoomOrganizerType.PLATFORM,
            RoomAccessType.PUBLIC,
            Instant.parse("2026-08-01T00:00:00Z"),
            Instant.parse("2026-08-01T00:00:00Z"),
            Instant.parse("2026-08-15T00:00:00Z"),
            Instant.parse("2026-08-31T00:00:00Z"),
            Instant.parse("2026-08-31T00:00:00Z"),
            Instant.parse("2026-09-01T00:00:00Z"),
            ZoneId.of("America/New_York")
        );
    }

    public static List<RoomEvaluationCommandFixture> evaluationCommands(RoomScheduleFixture schedule) {
        return List.of(
            command(schedule, 1, RoomEvaluationCommandType.INITIALIZE_EVALUATION, schedule.evaluationStartsAt().minusSeconds(300)),
            command(schedule, 2, RoomEvaluationCommandType.START_EVALUATION, schedule.evaluationStartsAt()),
            command(schedule, 3, RoomEvaluationCommandType.END_EVALUATION, schedule.evaluationEndsAt()),
            command(schedule, 4, RoomEvaluationCommandType.CONTINUE_AS_PRIVATE_BOT, schedule.evaluationEndsAt().plusSeconds(60)),
            command(schedule, 5, RoomEvaluationCommandType.STOP_BOT, schedule.evaluationEndsAt().plusSeconds(60))
        );
    }

    private static RoomScheduleFixture liveRoomSchedule(UUID roomId, RoomAccessType accessType, String recruitmentStart) {
        Instant recruitmentOpensAt = Instant.parse(recruitmentStart);
        Instant evaluationStartsAt = recruitmentOpensAt.plusSeconds(14 * 24 * 60 * 60L);
        Instant evaluationEndsAt = evaluationStartsAt.plusSeconds(14 * 24 * 60 * 60L);
        return new RoomScheduleFixture(
            CONTRACT_VERSION,
            roomId,
            SCHEDULE_VERSION,
            RoomCompetitionType.LIVE_PAPER,
            RoomOrganizerType.USER,
            accessType,
            recruitmentOpensAt,
            recruitmentOpensAt,
            evaluationStartsAt,
            evaluationStartsAt,
            evaluationEndsAt,
            evaluationEndsAt.plusSeconds(24 * 60 * 60L),
            ZoneId.of("America/New_York")
        );
    }

    private static RoomEvaluationCommandFixture command(
        RoomScheduleFixture schedule,
        int sequence,
        RoomEvaluationCommandType type,
        Instant effectiveAt
    ) {
        UUID commandId = UUID.fromString("50000000-0000-4000-8000-%012d".formatted(sequence));
        return new RoomEvaluationCommandFixture(
            CONTRACT_VERSION,
            commandId,
            type,
            schedule.roomId(),
            PARTICIPATION_ID,
            BOT_ID,
            EVALUATION_SEGMENT_ID,
            schedule.scheduleVersion(),
            schedule.evaluationStartsAt(),
            schedule.evaluationEndsAt(),
            effectiveAt,
            "sha256:" + Integer.toString(sequence).repeat(64)
        );
    }
}

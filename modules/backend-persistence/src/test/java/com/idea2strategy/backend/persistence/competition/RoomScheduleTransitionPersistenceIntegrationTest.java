package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.competition.RoomScheduleTransitionReport;
import com.idea2strategy.backend.persistence.botcontrol.BotRunCommandJooqAdapter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = RoomScheduleTransitionPersistenceIntegrationTest.TestApplication.class)
class RoomScheduleTransitionPersistenceIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000084");
    private static final UUID BOT_OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000085");
    private static final UUID OPERATOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000086");
    private static final UUID ROOM_ID = UUID.fromString("20000000-0000-4000-8000-000000000084");
    private static final Instant RECRUITMENT = Instant.parse("2026-08-02T01:00:00Z");
    private static final Instant EVALUATION = Instant.parse("2026-08-02T02:00:00Z");
    private static final Instant END = Instant.parse("2026-08-02T03:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private RoomScheduleTransitionJooqAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from operations.outbox_messages");
        jdbc.update("delete from competition.leaderboard_entries");
        jdbc.update("delete from competition.leaderboard_snapshots");
        jdbc.update("delete from bot.continuation_deadlines");
        jdbc.update("delete from competition.participation_events");
        jdbc.update("delete from competition.participations");
        jdbc.update("delete from competition.room_events");
        jdbc.update("delete from competition.room_schedules");
        jdbc.update("delete from competition.rooms");
        jdbc.update("delete from operations.operator_accounts where id = ?", OPERATOR_ID);
        jdbc.update("delete from bot.launch_snapshots");
        jdbc.update("delete from bot.bots");
        jdbc.update("truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
        jdbc.update("delete from identity.accounts");
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", OWNER_ID);
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", BOT_OWNER_ID);
        jdbc.update(
                "insert into operations.operator_accounts "
                        + "(id, status, created_at) values (?, 'ACTIVE', ?)",
                OPERATOR_ID,
                RECRUITMENT.minusSeconds(7200).atOffset(ZoneOffset.UTC));
    }

    @Test
    void catchesUpEveryDueBoundaryInOrderAndIsIdempotent() {
        seedRoom("DRAFT");
        seedActiveParticipation(1, OWNER_ID, "RUNNING");
        seedActiveParticipation(2, OWNER_ID, "RUNNING");
        Instant observedAt = END.plusSeconds(30);

        assertThat(adapter.advanceDue(observedAt, 10))
                .isEqualTo(new RoomScheduleTransitionReport(observedAt, 1, 3));
        assertThat(adapter.advanceDue(observedAt, 10))
                .isEqualTo(new RoomScheduleTransitionReport(observedAt, 0, 0));

        assertThat(jdbc.queryForObject(
                        "select status::text from competition.rooms where id = ?", String.class, ROOM_ID))
                .isEqualTo("ENDED");
        assertThat(jdbc.queryForObject(
                        "select ended_at from competition.rooms where id = ?",
                        java.time.OffsetDateTime.class,
                        ROOM_ID).toInstant())
                .isEqualTo(observedAt);
        assertThat(jdbc.queryForList(
                        "select event_sequence, event_type, resulting_status::text as status "
                                + "from competition.room_events where room_id = ? order by event_sequence",
                        ROOM_ID))
                .extracting(row -> row.get("event_type"))
                .containsExactly("RECRUITMENT_OPENED", "EVALUATION_STARTED", "EVALUATION_ENDED");
        assertThat(jdbc.queryForList(
                        "select payload_document ->> 'scheduledAt' as scheduled_at "
                                + "from competition.room_events where room_id = ? order by event_sequence",
                        ROOM_ID))
                .extracting(row -> row.get("scheduled_at"))
                .containsExactly(RECRUITMENT.toString(), EVALUATION.toString(), END.toString());
    }

    @Test
    void concurrentBatchInstancesApplyOneTransitionOnly() throws Exception {
        seedRoom("RECRUITING");
        seedActiveParticipation(1, OWNER_ID, "RUNNING");
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RoomScheduleTransitionReport> first = executor.submit(() -> {
                gate.await();
                return adapter.advanceDue(EVALUATION, 10);
            });
            Future<RoomScheduleTransitionReport> second = executor.submit(() -> {
                gate.await();
                return adapter.advanceDue(EVALUATION, 10);
            });
            gate.countDown();
            List<RoomScheduleTransitionReport> reports = List.of(first.get(), second.get());
            assertThat(reports).extracting(RoomScheduleTransitionReport::transitionsApplied).containsExactlyInAnyOrder(1, 0);
        }
        assertThat(jdbc.queryForObject(
                        "select count(*) from competition.room_events where room_id = ?", Integer.class, ROOM_ID))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select status::text from competition.rooms where id = ?", String.class, ROOM_ID))
                .isEqualTo("ENDED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from operations.outbox_messages "
                                + "where event_type = 'ROOM_INSUFFICIENT_PARTICIPATION_ENDED_NOTIFICATION'",
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    void endsAnUnderSubscribedRoomWithoutLeaderboardAndContinuesItsWaitingBotPrivately() {
        seedRoom("RECRUITING");
        UUID botId = seedActiveParticipation(1, BOT_OWNER_ID, "RUNNING");

        assertThat(adapter.advanceDue(EVALUATION.plusSeconds(5), 10))
                .isEqualTo(new RoomScheduleTransitionReport(EVALUATION.plusSeconds(5), 1, 1));
        assertThat(adapter.advanceDue(EVALUATION.plusSeconds(10), 10).transitionsApplied()).isZero();

        assertThat(jdbc.queryForObject(
                        "select status::text from competition.rooms where id = ?", String.class, ROOM_ID))
                .isEqualTo("ENDED");
        assertThat(jdbc.queryForObject(
                        "select status::text from competition.participations where bot_id = ?", String.class, botId))
                .isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject(
                        "select withdrawal_reason_code from competition.participations where bot_id = ?",
                        String.class, botId))
                .isEqualTo("INSUFFICIENT_PARTICIPATION");
        assertThat(jdbc.queryForObject(
                        "select due_at from bot.continuation_deadlines where bot_id = ?",
                        java.time.OffsetDateTime.class, botId).toInstant())
                .isEqualTo(EVALUATION.plusSeconds(5).plus(java.time.Duration.ofDays(30)));
        assertThat(jdbc.queryForObject(
                        "select count(*) from operations.outbox_messages where event_type = 'BOT_RUN_COMMAND'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from operations.outbox_messages "
                                + "where event_type = 'ROOM_INSUFFICIENT_PARTICIPATION_ENDED_NOTIFICATION'",
                        Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from competition.leaderboard_snapshots where room_id = ?",
                        Integer.class, ROOM_ID))
                .isZero();
        assertThat(jdbc.queryForList(
                        "select event_type, reason_code from competition.room_events where room_id = ?",
                        ROOM_ID))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.get("event_type")).isEqualTo("INSUFFICIENT_PARTICIPATION");
                    assertThat(event.get("reason_code")).isEqualTo("INSUFFICIENT_PARTICIPATION");
                });
    }

    @Test
    void preservesAStoppingBotWhenAnUnderSubscribedRoomEnds() {
        seedRoom("RECRUITING");
        UUID botId = seedActiveParticipation(1, BOT_OWNER_ID, "STOPPING");

        assertThat(adapter.advanceDue(EVALUATION, 10).transitionsApplied()).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                        "select lifecycle_status::text from bot.bots where id = ?", String.class, botId))
                .isEqualTo("STOPPING");
        assertThat(jdbc.queryForObject(
                        "select count(*) from bot.continuation_deadlines where bot_id = ?", Integer.class, botId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from operations.outbox_messages where event_type = 'BOT_RUN_COMMAND'",
                        Integer.class))
                .isZero();
    }

    @Test
    void countsPendingBacktestLedgersAndNeverStartsAnUnderSubscribedBacktestBotPrivately() {
        seedBacktestRoom("EVALUATING");
        UUID firstBotId = seedActiveParticipation(1, BOT_OWNER_ID, "RUNNING");
        UUID secondBotId = seedActiveParticipation(2, OWNER_ID, "RUNNING");
        jdbc.update(
                "update competition.participations set status = 'PENDING_LEDGER' where bot_id in (?, ?)",
                firstBotId,
                secondBotId);

        assertThat(adapter.advanceDue(EVALUATION.plusSeconds(5), 10).transitionsApplied()).isZero();
        assertThat(jdbc.queryForObject(
                        "select status::text from competition.rooms where id = ?", String.class, ROOM_ID))
                .isEqualTo("EVALUATING");

        jdbc.update("delete from competition.participations where bot_id = ?", secondBotId);
        assertThat(adapter.advanceDue(EVALUATION.plusSeconds(10), 10).transitionsApplied()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select status::text from competition.participations where bot_id = ?",
                        String.class,
                        firstBotId))
                .isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject(
                        "select count(*) from bot.continuation_deadlines where bot_id = ?",
                        Integer.class,
                        firstBotId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from operations.outbox_messages where event_type = 'BOT_RUN_COMMAND'",
                        Integer.class))
                .isZero();
    }

    private void seedRoom(String status) {
        seedRoom(status, "LIVE_PAPER", "USER");
    }

    private void seedBacktestRoom(String status) {
        seedRoom(status, "BACKTEST", "PLATFORM");
    }

    private void seedRoom(String status, String competitionType, String organizerType) {
        var createdAt = RECRUITMENT.minusSeconds(3600).atOffset(ZoneOffset.UTC);
        if ("BACKTEST".equals(competitionType)) {
            jdbc.update(
                    "insert into competition.rooms "
                            + "(id, competition_type, organizer_type, created_by_operator_id, name, access_type, "
                            + "status, created_at) values (?, 'BACKTEST', ?::competition.organizer_type, ?, "
                            + "'Schedule room', 'PUBLIC', ?::competition.room_status, ?::timestamptz)",
                    ROOM_ID, organizerType, OPERATOR_ID, status, createdAt);
        } else {
            jdbc.update(
                    "insert into competition.rooms "
                            + "(id, competition_type, organizer_type, creator_account_id, name, access_type, status, "
                            + "created_at) values (?, 'LIVE_PAPER', ?::competition.organizer_type, ?, "
                            + "'Schedule room', 'PUBLIC', ?::competition.room_status, ?::timestamptz)",
                    ROOM_ID, organizerType, OWNER_ID, status, createdAt);
        }
        jdbc.update(
                "insert into competition.room_schedules "
                        + "(room_id, recruitment_opens_at, participation_opens_at, evaluation_starts_at, "
                        + "participation_closes_at, evaluation_ends_at, finalization_deadline_at, timezone_name) "
                        + "values (?, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz, "
                        + "?::timestamptz, ?::timestamptz, 'America/New_York')",
                ROOM_ID,
                RECRUITMENT.atOffset(ZoneOffset.UTC),
                RECRUITMENT.atOffset(ZoneOffset.UTC),
                EVALUATION.atOffset(ZoneOffset.UTC),
                EVALUATION.atOffset(ZoneOffset.UTC),
                END.atOffset(ZoneOffset.UTC),
                END.plusSeconds(3600).atOffset(ZoneOffset.UTC));
    }

    private UUID seedActiveParticipation(int suffix, UUID ownerId, String lifecycleStatus) {
        UUID botId = UUID.fromString("30000000-0000-4000-8000-" + String.format("%012d", suffix));
        UUID participationId = UUID.fromString("40000000-0000-4000-8000-" + String.format("%012d", suffix));
        var createdAt = RECRUITMENT.minusSeconds(1800).atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, created_at, "
                        + "execution_eligible_from, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', ?, ?::bot.lifecycle_status, ?, ?, ?, 0, ?)",
                botId, ownerId, "Schedule bot " + suffix, lifecycleStatus, createdAt, createdAt,
                EVALUATION.atOffset(ZoneOffset.UTC), createdAt);
        jdbc.update(
                "insert into bot.launch_snapshots "
                        + "(bot_id, snapshot_schema_version, semantic_snapshot, presentation_snapshot, semantic_hash, "
                        + "presentation_hash, snapshot_hash, created_at) "
                        + "values (?, 'basic-launch-snapshot.v1', '{}'::jsonb, '{}'::jsonb, ?, ?, ?, ?)",
                botId, "semantic-" + suffix, "presentation-" + suffix, "snapshot-" + suffix, createdAt);
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at) "
                        + "values (?, ?, ?, ?, ?, 'REGISTERED', ?)",
                participationId, ROOM_ID, botId, ownerId, "schedule-bot-" + suffix, createdAt);
        return botId;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({RoomScheduleTransitionJooqAdapter.class, BotRunCommandJooqAdapter.class})
    static class TestApplication {}
}

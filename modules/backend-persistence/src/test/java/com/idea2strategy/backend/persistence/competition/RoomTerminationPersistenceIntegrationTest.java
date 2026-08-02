package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.competition.ParticipationExitAction;
import com.idea2strategy.backend.application.competition.RoomTerminationConflictException;
import com.idea2strategy.backend.persistence.botcontrol.BotRunCommandJooqAdapter;
import com.idea2strategy.backend.persistence.botcontrol.BotStopCommandJooqAdapter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = RoomTerminationPersistenceIntegrationTest.TestApplication.class)
class RoomTerminationPersistenceIntegrationTest {
    private static final UUID OWNER_ID = id(1);
    private static final UUID ROOM_ID = id(2);
    private static final UUID BOT_ID = id(3);
    private static final UUID PARTICIPATION_ID = id(4);
    private static final UUID OPERATOR_ID = id(5);
    private static final Instant NOW = Instant.parse("2026-08-02T05:00:00Z");

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

    @Autowired RoomTerminationJooqAdapter adapter;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void prepare() {
        jdbc.update("delete from operations.outbox_messages");
        jdbc.update("delete from bot.continuation_deadlines");
        jdbc.update("delete from competition.participation_events");
        jdbc.update("delete from competition.participations");
        jdbc.update("delete from competition.room_events");
        jdbc.update("delete from competition.room_schedules");
        jdbc.update("delete from competition.rooms");
        jdbc.update("delete from bot.launch_snapshots");
        jdbc.update("delete from bot.bots");
        jdbc.update("delete from operations.operator_accounts where id = ?", OPERATOR_ID);
        jdbc.update("delete from identity.accounts where id = ?", OWNER_ID);
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", OWNER_ID);
        jdbc.update(
                "insert into operations.operator_accounts "
                        + "(id, external_identity_key_hmac, status, mfa_enrolled_at, created_at) "
                        + "values (?, 'operator-e12', 'ACTIVE', ?, ?)",
                OPERATOR_ID, utc(NOW.minusSeconds(60)), utc(NOW.minusSeconds(60)));
    }

    @Test
    void withdrawsAWaitingBotIntoImmediatePrivateExecution() {
        seedRoom("RECRUITING", NOW.minusSeconds(60), NOW.plusSeconds(3600));
        seedParticipation(BOT_ID, PARTICIPATION_ID, "REGISTERED");

        adapter.withdrawOwned(
                ROOM_ID, PARTICIPATION_ID, OWNER_ID, ParticipationExitAction.CONTINUE_PRIVATE,
                "USER_REQUESTED", NOW);

        assertThat(value("select status::text from competition.participations where id = ?", PARTICIPATION_ID))
                .isEqualTo("WITHDRAWN");
        assertThat(instant("select execution_eligible_from from bot.bots where id = ?", BOT_ID)).isEqualTo(NOW);
        assertThat(instant("select due_at from bot.continuation_deadlines where bot_id = ?", BOT_ID))
                .isEqualTo(NOW.plusSeconds(30L * 24 * 60 * 60));
        assertThat(count("select count(*) from operations.outbox_messages where event_type = 'BOT_RUN_COMMAND'"))
                .isEqualTo(1);
        assertThatThrownBy(() -> adapter.withdrawOwned(
                        ROOM_ID, PARTICIPATION_ID, OWNER_ID, ParticipationExitAction.CONTINUE_PRIVATE,
                        "USER_REQUESTED", NOW.plusSeconds(1)))
                .isInstanceOf(RoomTerminationConflictException.class);
        assertThat(count("select count(*) from operations.outbox_messages")).isEqualTo(1);
    }

    @Test
    void withdrawsAnEvaluatingBotAndRequestsSettlement() {
        seedRoom("EVALUATING", NOW.minusSeconds(3600), NOW.minusSeconds(1800));
        seedParticipation(BOT_ID, PARTICIPATION_ID, "EVALUATING");

        adapter.withdrawOwned(
                ROOM_ID, PARTICIPATION_ID, OWNER_ID, ParticipationExitAction.STOP,
                "OWNER_STOPPED", NOW);

        assertThat(value("select lifecycle_status::text from bot.bots where id = ?", BOT_ID))
                .isEqualTo("STOPPING");
        assertThat(value("select stop_reason_code from bot.bots where id = ?", BOT_ID))
                .isEqualTo("ROOM_WITHDRAWAL");
        assertThat(count("select count(*) from operations.outbox_messages where event_type = 'BOT_STOP_COMMAND'"))
                .isEqualTo(1);
        assertThat(count("select count(*) from bot.continuation_deadlines")).isZero();
    }

    @Test
    void acceptsTheMaximumAuditReasonWithoutOverflowingTheBotStopCode() {
        seedRoom("EVALUATING", NOW.minusSeconds(3600), NOW.minusSeconds(1800));
        seedParticipation(BOT_ID, PARTICIPATION_ID, "EVALUATING");
        String reason = "R".repeat(80);

        adapter.withdrawOwned(
                ROOM_ID, PARTICIPATION_ID, OWNER_ID, ParticipationExitAction.STOP, reason, NOW);

        assertThat(value("select stop_reason_code from bot.bots where id = ?", BOT_ID))
                .isEqualTo("ROOM_WITHDRAWAL");
        assertThat(value("select withdrawal_reason_code from competition.participations where id = ?", PARTICIPATION_ID))
                .isEqualTo(reason);
    }

    @Test
    void creatorCancellationIsAllowedOnlyBeforeSubmissionOpens() {
        seedRoom("RECRUITING", NOW.plusSeconds(60), NOW.plusSeconds(3600));
        seedParticipation(BOT_ID, PARTICIPATION_ID, "REGISTERED");

        assertThat(adapter.cancelOwned(ROOM_ID, OWNER_ID, "CREATOR_REQUESTED", NOW)
                        .participationsTerminated())
                .isEqualTo(1);
        assertThat(value("select status::text from competition.rooms where id = ?", ROOM_ID))
                .isEqualTo("CANCELLED");
        assertThat(value("select event_type from competition.room_events where room_id = ?", ROOM_ID))
                .isEqualTo("ROOM_CANCELLED");
    }

    @Test
    void creatorCancellationAfterSubmissionLeavesTheRoomUntouched() {
        seedRoom("RECRUITING", NOW.minusSeconds(1), NOW.plusSeconds(3600));

        assertThatThrownBy(() -> adapter.cancelOwned(ROOM_ID, OWNER_ID, "TOO_LATE", NOW))
                .isInstanceOf(RoomTerminationConflictException.class);
        assertThat(value("select status::text from competition.rooms where id = ?", ROOM_ID))
                .isEqualTo("RECRUITING");
        assertThat(count("select count(*) from competition.room_events")).isZero();
    }

    @Test
    void platformInvalidationDetachesWaitingAndEvaluatingBotsWithAuditEvidence() {
        UUID secondBot = id(6);
        UUID secondParticipation = id(7);
        seedRoom("EVALUATING", NOW.minusSeconds(3600), NOW.minusSeconds(1800));
        seedParticipation(BOT_ID, PARTICIPATION_ID, "EVALUATING");
        seedParticipation(secondBot, secondParticipation, "REGISTERED");

        assertThat(adapter.invalidate(ROOM_ID, OPERATOR_ID, "OFFICIAL_LEDGER_INTEGRITY", NOW)
                        .participationsTerminated())
                .isEqualTo(2);

        assertThat(value("select status::text from competition.rooms where id = ?", ROOM_ID))
                .isEqualTo("INVALIDATED");
        assertThat(value("select invalidation_reason_code from competition.rooms where id = ?", ROOM_ID))
                .isEqualTo("OFFICIAL_LEDGER_INTEGRITY");
        assertThat(value("select status::text from competition.participations where id = ?", PARTICIPATION_ID))
                .isEqualTo("EVALUATION_FAILED");
        assertThat(value("select status::text from competition.participations where id = ?", secondParticipation))
                .isEqualTo("WITHDRAWN");
        assertThat(count("select count(*) from bot.continuation_deadlines")).isEqualTo(2);
        assertThat(count("select count(*) from operations.outbox_messages where event_type = 'BOT_RUN_COMMAND'"))
                .isEqualTo(1);
        assertThat(count("select count(*) from competition.participation_events "
                + "where event_type = 'ROOM_INVALIDATED'"))
                .isEqualTo(2);
    }

    @Test
    void platformInvalidationPreservesAnAlreadyStoppingBot() {
        UUID secondBot = id(6);
        UUID secondParticipation = id(7);
        seedRoom("EVALUATING", NOW.minusSeconds(3600), NOW.minusSeconds(1800));
        seedParticipation(BOT_ID, PARTICIPATION_ID, "EVALUATING");
        seedParticipation(secondBot, secondParticipation, "REGISTERED");
        jdbc.update(
                "update bot.bots set lifecycle_status = 'STOPPING'::bot.lifecycle_status, "
                        + "stop_requested_at = ?, stop_reason_code = 'OWNER_STOPPED' where id = ?",
                utc(NOW.minusSeconds(10)), BOT_ID);

        assertThat(adapter.invalidate(ROOM_ID, OPERATOR_ID, "SYSTEM_SAFETY", NOW)
                        .participationsTerminated())
                .isEqualTo(2);
        assertThat(value("select lifecycle_status::text from bot.bots where id = ?", BOT_ID))
                .isEqualTo("STOPPING");
        assertThat(count("select count(*) from bot.continuation_deadlines where bot_id = '" + BOT_ID + "'"))
                .isZero();
    }

    @Test
    void withdrawalSerializesBehindTheRoomEndTransition() throws Exception {
        seedRoom("EVALUATING", NOW.minusSeconds(3600), NOW.minusSeconds(1800));
        seedParticipation(BOT_ID, PARTICIPATION_ID, "EVALUATING");
        var roomLocked = new CountDownLatch(1);
        var releaseRoom = new CountDownLatch(1);
        var transactions = new TransactionTemplate(transactionManager);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var ending = executor.submit(() -> transactions.executeWithoutResult(ignored -> {
                jdbc.queryForObject("select id from competition.rooms where id = ? for update", UUID.class, ROOM_ID);
                jdbc.update("update competition.rooms set status = 'ENDED'::competition.room_status where id = ?", ROOM_ID);
                roomLocked.countDown();
                try {
                    releaseRoom.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(roomLocked.await(5, TimeUnit.SECONDS)).isTrue();
            var withdrawal = executor.submit(() -> adapter.withdrawOwned(
                    ROOM_ID, PARTICIPATION_ID, OWNER_ID, ParticipationExitAction.CONTINUE_PRIVATE,
                    "TOO_LATE", NOW));

            assertThat(withdrawal.isDone()).isFalse();
            releaseRoom.countDown();
            ending.get();
            assertThatThrownBy(withdrawal::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(com.idea2strategy.backend.application.competition.RoomTerminationAccessException.class);
        } finally {
            releaseRoom.countDown();
        }
        assertThat(value("select status::text from competition.participations where id = ?", PARTICIPATION_ID))
                .isEqualTo("EVALUATING");
    }

    private void seedRoom(String status, Instant participationOpensAt, Instant evaluationStartsAt) {
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, creator_account_id, name, access_type, status, created_at) "
                        + "values (?, 'LIVE_PAPER', 'USER', ?, 'E12 Room', 'PUBLIC', "
                        + "?::competition.room_status, ?)",
                ROOM_ID, OWNER_ID, status, utc(NOW.minusSeconds(7200)));
        jdbc.update(
                "insert into competition.room_schedules "
                        + "(room_id, recruitment_opens_at, participation_opens_at, evaluation_starts_at, "
                        + "participation_closes_at, evaluation_ends_at, finalization_deadline_at, timezone_name) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'UTC')",
                ROOM_ID, utc(NOW.minusSeconds(7200)), utc(participationOpensAt), utc(evaluationStartsAt),
                utc(NOW.plusSeconds(7200)), utc(NOW.plusSeconds(10800)), utc(NOW.plusSeconds(14400)));
    }

    private void seedParticipation(UUID botId, UUID participationId, String status) {
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, created_at, "
                        + "execution_eligible_from, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', ?, 'RUNNING', ?, ?, ?, 0, ?)",
                botId, OWNER_ID, "E12 Bot " + botId, utc(NOW.minusSeconds(3600)), utc(NOW.minusSeconds(3600)),
                utc(NOW.plusSeconds(3600)), utc(NOW.minusSeconds(3600)));
        jdbc.update(
                "insert into bot.launch_snapshots "
                        + "(bot_id, snapshot_schema_version, semantic_snapshot, presentation_snapshot, semantic_hash, "
                        + "presentation_hash, snapshot_hash, created_at) "
                        + "values (?, 'basic-launch-snapshot.v1', '{}'::jsonb, '{}'::jsonb, ?, ?, ?, ?)",
                botId, "semantic-" + botId, "presentation-" + botId, "snapshot-" + botId,
                utc(NOW.minusSeconds(3600)));
        boolean evaluating = "EVALUATING".equals(status);
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at, evaluation_started_at) "
                        + "values (?, ?, ?, ?, ?, ?::competition.participation_status, ?, ?)",
                participationId, ROOM_ID, botId, OWNER_ID, "alias-" + participationId, status,
                utc(NOW.minusSeconds(1800)), evaluating ? utc(NOW.minusSeconds(900)) : null);
    }

    private String value(String sql, Object argument) {
        return jdbc.queryForObject(sql, String.class, argument);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private Instant instant(String sql, Object argument) {
        return jdbc.queryForObject(sql, OffsetDateTime.class, argument).toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("88000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({RoomTerminationJooqAdapter.class, BotRunCommandJooqAdapter.class, BotStopCommandJooqAdapter.class})
    static class TestApplication {}
}

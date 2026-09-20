package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.competition.FinalLeaderboardEntry;
import com.idea2strategy.backend.application.competition.FinalRoomResult;
import com.idea2strategy.backend.application.competition.FinalRoomResultWriteDecision;
import com.idea2strategy.backend.application.competition.PrivateContinuationTransitionConflictException;
import com.idea2strategy.backend.application.competition.PrivateContinuationTransitionDecision;
import com.idea2strategy.backend.application.competition.PostEvaluationStopTransitionDecision;
import com.idea2strategy.backend.persistence.botcontrol.BotStopCommandJooqAdapter;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
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
@SpringBootTest(classes = PrivateContinuationTransitionPersistenceIntegrationTest.TestApplication.class)
class PrivateContinuationTransitionPersistenceIntegrationTest {
    private static final Instant CUTOFF = Instant.parse("2026-08-02T05:00:00Z");
    private static final Instant ENDED_AT = CUTOFF.plusSeconds(20);
    private static final Instant OBSERVED_AT = ENDED_AT.plusSeconds(10);
    private static final Instant EXPECTED_DUE_AT = ENDED_AT.plus(Duration.ofDays(30));

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

    @Autowired PrivateContinuationTransitionJooqAdapter adapter;
    @Autowired FinalRoomResultJooqAdapter finalResultAdapter;
    @Autowired PostEvaluationStopTransitionJooqAdapter stopTransitionAdapter;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        jdbc.update("delete from operations.outbox_messages");
        jdbc.update("delete from competition.participation_events");
        jdbc.update("delete from bot.continuation_deadlines");
        jdbc.update("delete from competition.room_final_access_grants");
        jdbc.update("delete from competition.leaderboard_entries");
        jdbc.update("delete from competition.leaderboard_snapshots");
        jdbc.update("delete from performance.bot_snapshots");
        jdbc.update("delete from competition.live_evaluation_segments");
        jdbc.update("delete from competition.participations");
        jdbc.update("delete from competition.room_rules");
        jdbc.update("delete from competition.room_schedules");
        jdbc.update("delete from competition.rooms");
        jdbc.update("delete from bot.launch_snapshots");
        jdbc.update("delete from bot.bots");
        jdbc.update("delete from competition.scoring_template_versions where id = ?", TEMPLATE_ID);
        jdbc.update("delete from trading.fee_policy_versions where id = ?", FEE_ID);
        jdbc.update("delete from trading.buying_power_buffer_policy_versions where id = ?", BUFFER_ID);
        jdbc.execute("truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
        jdbc.update("delete from identity.accounts where id in (?, ?, ?)", OWNER_ID, CREATOR_ID, FAILED_OWNER_ID);
        seedReadyCandidate();
    }

    @Test
    void activatesPrivateContinuationWithoutChangingOfficialOrTradingState() {
        String resultHash = jdbc.queryForObject(
                "select result_hash from competition.leaderboard_snapshots where id = ?",
                String.class, FINAL_SNAPSHOT_ID);

        assertThat(adapter.transitionNext(OBSERVED_AT))
                .isEqualTo(PrivateContinuationTransitionDecision.APPLIED);

        assertThat(instant("select due_at from bot.continuation_deadlines where bot_id = ?", BOT_ID))
                .isEqualTo(EXPECTED_DUE_AT);
        assertThat(count("select count(*) from competition.participation_events where event_type = "
                + "'PRIVATE_CONTINUATION_ACTIVATED'")).isOne();
        assertThat(count("select count(*) from operations.outbox_messages where event_type = "
                + "'PRIVATE_CONTINUATION_ACTIVATED_NOTIFICATION'")).isOne();
        assertThat(jdbc.queryForObject(
                "select payload_document->'channels' = '[\"IN_APP\", \"EMAIL\"]'::jsonb "
                        + "from operations.outbox_messages where event_type = "
                        + "'PRIVATE_CONTINUATION_ACTIVATED_NOTIFICATION'",
                Boolean.class)).isTrue();
        assertThat(count("select count(*) from operations.outbox_messages where event_type in "
                + "('BOT_RUN_COMMAND', 'BOT_STOP_COMMAND')")).isZero();
        assertThat(jdbc.queryForObject("select lifecycle_status::text from bot.bots where id = ?", String.class, BOT_ID))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("select status::text from competition.participations where id = ?", String.class,
                PARTICIPATION_ID)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "select result_hash from competition.leaderboard_snapshots where id = ?",
                String.class, FINAL_SNAPSHOT_ID)).isEqualTo(resultHash);

        assertThat(adapter.transitionNext(OBSERVED_AT.plusSeconds(1)))
                .isEqualTo(PrivateContinuationTransitionDecision.NO_READY_CANDIDATE);
        assertThat(count("select count(*) from bot.continuation_deadlines")).isOne();
        assertThat(count("select count(*) from competition.participation_events where event_type = "
                + "'PRIVATE_CONTINUATION_ACTIVATED'")).isOne();
    }

    @Test
    void preservesAnAlreadyRenewedLaterDeadline() {
        Instant renewedDueAt = EXPECTED_DUE_AT.plus(Duration.ofDays(30));
        jdbc.update(
                "insert into bot.continuation_deadlines "
                        + "(bot_id, due_at, last_renewed_at, renewal_sequence, created_at, updated_at) "
                        + "values (?, ?, ?, 1, ?, ?)",
                BOT_ID, utc(renewedDueAt), utc(ENDED_AT.plus(Duration.ofDays(1))), utc(ENDED_AT), utc(ENDED_AT));

        assertThat(adapter.transitionNext(OBSERVED_AT))
                .isEqualTo(PrivateContinuationTransitionDecision.APPLIED);
        assertThat(instant("select due_at from bot.continuation_deadlines where bot_id = ?", BOT_ID))
                .isEqualTo(renewedDueAt);
        assertThat(jdbc.queryForObject(
                "select renewal_sequence from bot.continuation_deadlines where bot_id = ?",
                Long.class, BOT_ID)).isOne();
    }

    @Test
    void rejectsIncompleteGatesAndNonContinuingBotsWithoutSideEffects() {
        jdbc.update("update competition.participations set action_locked_at = null where id = ?", PARTICIPATION_ID);
        assertNotReady();
        jdbc.update("update competition.participations set action_locked_at = ? where id = ?", utc(CUTOFF), PARTICIPATION_ID);

        jdbc.update("update competition.participations set post_room_action = 'STOP' where id = ?", PARTICIPATION_ID);
        assertNotReady();
        jdbc.update("update competition.participations set post_room_action = 'CONTINUE_PRIVATE' where id = ?",
                PARTICIPATION_ID);

        jdbc.update("update competition.participations set status = 'EVALUATING' where id = ?", PARTICIPATION_ID);
        assertNotReady();
        jdbc.update("update competition.participations set status = 'COMPLETED' where id = ?", PARTICIPATION_ID);

        jdbc.update("update competition.live_evaluation_segments set finalized_at = null where id = ?", SEGMENT_ID);
        assertNotReady();
        jdbc.update("update competition.live_evaluation_segments set finalized_at = ? where id = ?", utc(CUTOFF), SEGMENT_ID);

        jdbc.update("update competition.leaderboard_snapshots set status = 'PUBLISHED' where id = ?", FINAL_SNAPSHOT_ID);
        assertNotReady();
        jdbc.update("update competition.leaderboard_snapshots set status = 'FINAL' where id = ?", FINAL_SNAPSHOT_ID);

        jdbc.update("update competition.rooms set status = 'INVALIDATED' where id = ?", ROOM_ID);
        assertNotReady();
        jdbc.update("update competition.rooms set status = 'ENDED' where id = ?", ROOM_ID);

        jdbc.update("update bot.bots set lifecycle_status = 'STOPPING' where id = ?", BOT_ID);
        assertNotReady();
    }

    @Test
    void rollsBackWhenAnUnrenewedDeadlineConflicts() {
        jdbc.update(
                "insert into bot.continuation_deadlines "
                        + "(bot_id, due_at, renewal_sequence, created_at, updated_at) values (?, ?, 0, ?, ?)",
                BOT_ID, utc(EXPECTED_DUE_AT.plusSeconds(1)), utc(ENDED_AT), utc(ENDED_AT));

        assertThatThrownBy(() -> adapter.transitionNext(OBSERVED_AT))
                .isInstanceOf(PrivateContinuationTransitionConflictException.class)
                .hasMessageContaining("conflicts");
        assertThat(count("select count(*) from competition.participation_events where event_type = "
                + "'PRIVATE_CONTINUATION_ACTIVATED'")).isZero();
        assertThat(count("select count(*) from operations.outbox_messages where event_type = "
                + "'PRIVATE_CONTINUATION_ACTIVATED_NOTIFICATION'")).isZero();
    }

    @Test
    void twoWorkersCreateTheTransitionExactlyOnce() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.of(
                            () -> adapter.transitionNext(OBSERVED_AT),
                            () -> adapter.transitionNext(OBSERVED_AT)))
                    .stream().map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    }).toList();
            assertThat(results).containsExactlyInAnyOrder(
                    PrivateContinuationTransitionDecision.APPLIED,
                    PrivateContinuationTransitionDecision.NO_READY_CANDIDATE);
        }
        assertThat(count("select count(*) from bot.continuation_deadlines")).isOne();
        assertThat(count("select count(*) from competition.participation_events where event_type = "
                + "'PRIVATE_CONTINUATION_ACTIVATED'")).isOne();
    }

    @Test
    void finalResultLocksTheTerminalParticipationChoiceForTheContinuationHandoff() {
        jdbc.update("delete from competition.leaderboard_entries");
        jdbc.update("delete from competition.leaderboard_snapshots");
        jdbc.update("update competition.participations set action_locked_at = null where id = ?", PARTICIPATION_ID);
        jdbc.update("update competition.rooms set access_type = 'SECRET' where id = ?", ROOM_ID);
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", FAILED_OWNER_ID);
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, "
                        + "execution_eligible_from, created_at, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', 'Failed room bot', 'STOPPED', ?, ?, ?, 0, ?)",
                FAILED_BOT_ID, FAILED_OWNER_ID, utc(ENDED_AT), utc(CUTOFF), utc(CUTOFF), utc(ENDED_AT));
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at, "
                        + "evaluation_started_at, evaluation_finished_at, evaluation_failure_code) "
                        + "values (?, ?, ?, ?, 'failed-room-bot', 'EVALUATION_FAILED', ?, ?, ?, 'LEDGER_OPEN_FAILED')",
                FAILED_PARTICIPATION_ID, ROOM_ID, FAILED_BOT_ID, FAILED_OWNER_ID,
                utc(CUTOFF.minusSeconds(600)), utc(CUTOFF.minusSeconds(600)), utc(CUTOFF));
        var result = new FinalRoomResult(
                FINAL_SNAPSHOT_ID, ROOM_ID, TEMPLATE_ID, CUTOFF, "sha256:result", ENDED_AT,
                List.of(new FinalLeaderboardEntry(
                        PARTICIPATION_ID, PERFORMANCE_SNAPSHOT_ID, 1, false,
                        java.math.BigDecimal.TEN, "ELIGIBLE", null, "sha256:provenance",
                        "{}", "{}")));

        assertThat(finalResultAdapter.save(result)).isEqualTo(FinalRoomResultWriteDecision.CREATED);
        assertThat(finalResultAdapter.save(result))
                .isEqualTo(FinalRoomResultWriteDecision.ALREADY_FINALIZED_IDENTICALLY);

        assertThat(instant("select action_locked_at from competition.participations where id = ?", PARTICIPATION_ID))
                .isEqualTo(CUTOFF);
        assertThat(jdbc.queryForList(
                        "select account_id, eligibility_basis from competition.room_final_access_grants "
                                + "where room_id = ? order by eligibility_basis",
                        ROOM_ID))
                .extracting(row -> List.of(row.get("account_id"), row.get("eligibility_basis")))
                .containsExactly(
                        List.of(OWNER_ID, "ACTIVE_PARTICIPANT"),
                        List.of(FAILED_OWNER_ID, "ACTIVE_PARTICIPANT"),
                        List.of(CREATOR_ID, "CREATOR"));
    }

    @Test
    void dispatchesTheEstablishedStopFlowOnlyAfterOfficialFinalization() {
        String resultHash = jdbc.queryForObject(
                "select result_hash from competition.leaderboard_snapshots where id = ?",
                String.class, FINAL_SNAPSHOT_ID);
        jdbc.update("update competition.participations set post_room_action = 'STOP' where id = ?", PARTICIPATION_ID);

        assertThat(stopTransitionAdapter.transitionNext(OBSERVED_AT))
                .isEqualTo(PostEvaluationStopTransitionDecision.APPLIED);

        assertThat(jdbc.queryForObject(
                "select lifecycle_status::text from bot.bots where id = ?", String.class, BOT_ID))
                .isEqualTo("STOPPING");
        assertThat(count("select count(*) from operations.outbox_messages where event_type = 'BOT_STOP_COMMAND'"))
                .isOne();
        assertThat(jdbc.queryForObject(
                "select payload_document->>'reasonCode' from operations.outbox_messages "
                        + "where event_type = 'BOT_STOP_COMMAND'",
                String.class)).isEqualTo("ROOM_EVALUATION_ENDED");
        assertThat(count("select count(*) from competition.participation_events where event_type = "
                + "'POST_EVALUATION_STOP_DISPATCHED'")).isOne();
        assertThat(count("select count(*) from bot.continuation_deadlines")).isZero();
        assertThat(jdbc.queryForObject(
                "select result_hash from competition.leaderboard_snapshots where id = ?",
                String.class, FINAL_SNAPSHOT_ID)).isEqualTo(resultHash);
        assertThat(stopTransitionAdapter.transitionNext(OBSERVED_AT.plusSeconds(1)))
                .isEqualTo(PostEvaluationStopTransitionDecision.NO_READY_CANDIDATE);
        assertThat(count("select count(*) from operations.outbox_messages where event_type = 'BOT_STOP_COMMAND'"))
                .isOne();
    }

    @Test
    void treatsALockedMissingChoiceAsStopAndExcludesContinuePrivate() {
        jdbc.update(
                "update competition.participations set post_room_action = null, action_recorded_at = null where id = ?",
                PARTICIPATION_ID);
        assertThat(stopTransitionAdapter.transitionNext(OBSERVED_AT))
                .isEqualTo(PostEvaluationStopTransitionDecision.APPLIED);
        assertThat(count("select count(*) from operations.outbox_messages where event_type = 'BOT_STOP_COMMAND'"))
                .isOne();

        prepare();
        assertThat(stopTransitionAdapter.transitionNext(OBSERVED_AT))
                .isEqualTo(PostEvaluationStopTransitionDecision.NO_READY_CANDIDATE);
        assertThat(count("select count(*) from operations.outbox_messages where event_type = 'BOT_STOP_COMMAND'"))
                .isZero();
    }

    @Test
    void twoStopWorkersDispatchExactlyOnce() throws Exception {
        jdbc.update("update competition.participations set post_room_action = 'STOP' where id = ?", PARTICIPATION_ID);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.of(
                            () -> stopTransitionAdapter.transitionNext(OBSERVED_AT),
                            () -> stopTransitionAdapter.transitionNext(OBSERVED_AT)))
                    .stream().map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new RuntimeException(exception);
                        }
                    }).toList();
            assertThat(results).containsExactlyInAnyOrder(
                    PostEvaluationStopTransitionDecision.APPLIED,
                    PostEvaluationStopTransitionDecision.NO_READY_CANDIDATE);
        }
        assertThat(count("select count(*) from operations.outbox_messages where event_type = 'BOT_STOP_COMMAND'"))
                .isOne();
        assertThat(count("select count(*) from competition.participation_events where event_type = "
                + "'POST_EVALUATION_STOP_DISPATCHED'")).isOne();
    }

    private void assertNotReady() {
        assertThat(adapter.transitionNext(OBSERVED_AT))
                .isEqualTo(PrivateContinuationTransitionDecision.NO_READY_CANDIDATE);
        assertThat(count("select count(*) from bot.continuation_deadlines")).isZero();
        assertThat(count("select count(*) from competition.participation_events where event_type = "
                + "'PRIVATE_CONTINUATION_ACTIVATED'")).isZero();
    }

    private void seedReadyCandidate() {
        Instant publishedAt = CUTOFF.minus(Duration.ofDays(1));
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE'), (?, 'ACTIVE')",
                OWNER_ID, CREATOR_ID);
        jdbc.update(
                "insert into competition.scoring_template_versions "
                        + "(id, template_code, version, rules_document, rules_hash, published_at) "
                        + "values (?, 'TOTAL_RETURN', '1', '{}'::jsonb, ?, ?)",
                TEMPLATE_ID, "sha256:" + "1".repeat(64), utc(publishedAt));
        jdbc.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'DEFAULT', '1', 20, 'v1', ?, ?, ?)",
                FEE_ID, "sha256:" + "2".repeat(64), utc(publishedAt), utc(publishedAt));
        jdbc.update(
                "insert into trading.buying_power_buffer_policy_versions "
                        + "(id, policy_code, version, buffer_bps, rounding_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'DEFAULT', '1', 0, 'v1', ?, ?, ?)",
                BUFFER_ID, "sha256:" + "3".repeat(64), utc(publishedAt), utc(publishedAt));
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, creator_account_id, name, access_type, "
                        + "status, created_at, ended_at) values (?, 'LIVE_PAPER', 'USER', ?, "
                        + "'Continuation room', 'PUBLIC', 'ENDED', ?, ?)",
                ROOM_ID, CREATOR_ID, utc(publishedAt), utc(ENDED_AT));
        jdbc.update(
                "insert into competition.room_rules "
                        + "(room_id, scoring_template_version_id, initial_cash_amount, bot_participation_limit, "
                        + "per_account_bot_limit, eligibility_document, market_scope_document, scoring_parameters, "
                        + "fee_policy_id, slippage_rate_bps, buying_power_buffer_policy_id, precision_rules_version, "
                        + "rules_hash, locked_at) values (?, ?, 100000, 10, 2, '{}'::jsonb, '{}'::jsonb, "
                        + "'{}'::jsonb, ?, 5, ?, 'v1', ?, ?)",
                ROOM_ID, TEMPLATE_ID, FEE_ID, BUFFER_ID, "sha256:" + "4".repeat(64), utc(publishedAt));
        jdbc.update(
                "insert into competition.room_schedules "
                        + "(room_id, recruitment_opens_at, participation_opens_at, evaluation_starts_at, "
                        + "participation_closes_at, evaluation_ends_at, finalization_deadline_at, timezone_name) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'UTC')",
                ROOM_ID, utc(publishedAt), utc(publishedAt), utc(CUTOFF.minusSeconds(600)),
                utc(CUTOFF.minusSeconds(600)), utc(CUTOFF), utc(CUTOFF.plusSeconds(3600)));
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, "
                        + "execution_eligible_from, created_at, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', 'Continuation bot', 'RUNNING', ?, ?, ?, 0, ?)",
                BOT_ID, OWNER_ID, utc(publishedAt), utc(publishedAt), utc(publishedAt), utc(publishedAt));
        jdbc.update(
                "insert into bot.launch_snapshots "
                        + "(bot_id, snapshot_schema_version, semantic_snapshot, presentation_snapshot, "
                        + "semantic_hash, presentation_hash, snapshot_hash, created_at) "
                        + "values (?, 'basic-launch-snapshot.v1', '{}'::jsonb, '{}'::jsonb, ?, ?, ?, ?)",
                BOT_ID, "semantic", "presentation", "launch", utc(publishedAt));
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at, "
                        + "evaluation_started_at, evaluation_finished_at, post_room_action, "
                        + "action_recorded_at, action_locked_at) "
                        + "values (?, ?, ?, ?, 'private-bot', 'COMPLETED', ?, ?, ?, 'CONTINUE_PRIVATE', ?, ?)",
                PARTICIPATION_ID, ROOM_ID, BOT_ID, OWNER_ID, utc(publishedAt),
                utc(CUTOFF.minusSeconds(600)), utc(CUTOFF), utc(CUTOFF.minusSeconds(60)), utc(CUTOFF));
        jdbc.update(
                "insert into competition.live_evaluation_segments "
                        + "(id, participation_id, segment_type, starts_at, ends_at, start_event_sequence, "
                        + "end_event_sequence, initial_state_hash, final_state_hash, source_set_hash, "
                        + "virtual_liquidation_document, finalized_at) "
                        + "values (?, ?, 'OFFICIAL_EVALUATION', ?, ?, 1, 2, ?, ?, ?, '{}'::jsonb, ?)",
                SEGMENT_ID, PARTICIPATION_ID, utc(CUTOFF.minusSeconds(600)), utc(CUTOFF),
                "sha256:initial", "sha256:final", "sha256:sources", utc(CUTOFF));
        jdbc.update(
                "insert into performance.bot_snapshots "
                        + "(id, bot_id, snapshot_type, source_event_sequence, evaluated_at, equity_amount, "
                        + "total_return_pct, max_drawdown_pct, metrics_document, input_hash, "
                        + "calculation_rules_version, snapshot_hash, created_at) "
                        + "values (?, ?, 'LEADERBOARD_CUTOFF', 2, ?, 110000, 10, 2, '{}'::jsonb, ?, ?, ?, ?)",
                PERFORMANCE_SNAPSHOT_ID, BOT_ID, utc(CUTOFF), "sha256:input", "v1", "sha256:snapshot", utc(CUTOFF));
        jdbc.update(
                "insert into competition.leaderboard_snapshots "
                        + "(id, room_id, scoring_template_version_id, cutoff_at, status, result_hash, created_at) "
                        + "values (?, ?, ?, ?, 'FINAL', ?, ?)",
                FINAL_SNAPSHOT_ID, ROOM_ID, TEMPLATE_ID, utc(CUTOFF), "sha256:result", utc(ENDED_AT));
        jdbc.update(
                "insert into competition.leaderboard_entries "
                        + "(snapshot_id, participation_id, performance_snapshot_id, rank, is_joint_rank, "
                        + "eligibility_status, score, tie_break_document, calculation_document) "
                        + "values (?, ?, ?, 1, false, 'ELIGIBLE', 10, '{}'::jsonb, '{}'::jsonb)",
                FINAL_SNAPSHOT_ID, PARTICIPATION_ID, PERFORMANCE_SNAPSHOT_ID);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private Instant instant(String sql, Object... args) {
        return jdbc.queryForObject(sql, java.time.OffsetDateTime.class, args).toInstant();
    }

    private static java.time.OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("e2600000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    private static final UUID OWNER_ID = id(1);
    private static final UUID ROOM_ID = id(2);
    private static final UUID BOT_ID = id(3);
    private static final UUID PARTICIPATION_ID = id(4);
    private static final UUID TEMPLATE_ID = id(5);
    private static final UUID SEGMENT_ID = id(6);
    private static final UUID PERFORMANCE_SNAPSHOT_ID = id(7);
    private static final UUID FINAL_SNAPSHOT_ID = id(8);
    private static final UUID FEE_ID = id(9);
    private static final UUID BUFFER_ID = id(10);
    private static final UUID CREATOR_ID = id(11);
    private static final UUID FAILED_OWNER_ID = id(12);
    private static final UUID FAILED_BOT_ID = id(13);
    private static final UUID FAILED_PARTICIPATION_ID = id(14);

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        PrivateContinuationTransitionJooqAdapter.class,
        FinalRoomResultJooqAdapter.class,
        PostEvaluationStopTransitionJooqAdapter.class,
        BotStopCommandJooqAdapter.class
    })
    static class TestApplication {}
}

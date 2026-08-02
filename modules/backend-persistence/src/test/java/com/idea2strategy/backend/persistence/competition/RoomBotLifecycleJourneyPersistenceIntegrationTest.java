package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.competition.FinalLeaderboardEntry;
import com.idea2strategy.backend.application.competition.FinalRoomResult;
import com.idea2strategy.backend.application.competition.FinalRoomResultWriteDecision;
import com.idea2strategy.backend.application.competition.PostEvaluationAction;
import com.idea2strategy.backend.application.competition.PostEvaluationStopTransitionDecision;
import com.idea2strategy.backend.application.competition.PrivateContinuationTransitionDecision;
import com.idea2strategy.backend.persistence.botcontrol.BotContinuationJooqAdapter;
import com.idea2strategy.backend.persistence.botcontrol.BotStopCommandJooqAdapter;
import com.idea2strategy.backend.persistence.botcontrol.ExpiredBotStopJooqQueryAdapter;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
@SpringBootTest(classes = RoomBotLifecycleJourneyPersistenceIntegrationTest.TestApplication.class)
class RoomBotLifecycleJourneyPersistenceIntegrationTest {
    private static final Instant CUTOFF = Instant.parse("2026-08-02T05:00:00Z");
    private static final Instant CHOICE_AT = CUTOFF.minusSeconds(60);
    private static final Instant ENDED_AT = CUTOFF.plusSeconds(20);
    private static final Instant TRANSITION_AT = ENDED_AT.plusSeconds(10);
    private static final Instant INITIAL_DUE_AT = ENDED_AT.plus(Duration.ofDays(30));
    private static final String RESULT_HASH = "sha256:e90-final-result";
    private static final String LAUNCH_HASH = "e90-launch-snapshot";

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

    @Autowired PostEvaluationChoiceJooqAdapter choiceAdapter;
    @Autowired FinalRoomResultJooqAdapter finalResultAdapter;
    @Autowired PrivateContinuationTransitionJooqAdapter continuationTransition;
    @Autowired PostEvaluationStopTransitionJooqAdapter stopTransition;
    @Autowired BotContinuationJooqAdapter botContinuation;
    @Autowired ExpiredBotStopJooqQueryAdapter expiredBotQuery;
    @Autowired BotStopCommandJooqAdapter botStopCommand;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void prepareEvaluatingRoomBot() {
        jdbc.update("delete from operations.audit_events where target_id = ?", BOT_ID);
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
        jdbc.update("delete from identity.accounts where id in (?, ?)", OWNER_ID, CREATOR_ID);
        seedEvaluation();
    }

    @Test
    void keepsTheSameBotThroughChoiceContinuationRenewalAndExactExpiryStop() {
        choiceAdapter.updateOwned(
                ROOM_ID, PARTICIPATION_ID, OWNER_ID, PostEvaluationAction.CONTINUE_PRIVATE, CHOICE_AT);
        publishFinalResult();

        assertThat(continuationTransition.transitionNext(TRANSITION_AT))
                .isEqualTo(PrivateContinuationTransitionDecision.APPLIED);
        assertThat(continuationTransition.transitionNext(TRANSITION_AT.plusSeconds(1)))
                .isEqualTo(PrivateContinuationTransitionDecision.NO_READY_CANDIDATE);
        assertThat(instant("select due_at from bot.continuation_deadlines where bot_id = ?", BOT_ID))
                .isEqualTo(INITIAL_DUE_AT);

        Instant renewedAt = INITIAL_DUE_AT.minus(Duration.ofDays(1));
        Instant renewedDueAt = renewedAt.plus(Duration.ofDays(30));
        assertThat(botContinuation.renewOwned(BOT_ID, OWNER_ID, renewedAt).orElseThrow().dueAt())
                .isEqualTo(renewedDueAt);
        assertThat(expiredBotQuery.findExpired(renewedDueAt.minusSeconds(1), 10)).isEmpty();

        var candidate = expiredBotQuery.findExpired(renewedDueAt, 10).getFirst();
        assertThat(candidate.botId()).isEqualTo(BOT_ID);
        assertThat(botStopCommand.issueExpired(candidate, renewedDueAt)).isTrue();
        assertThat(botStopCommand.issueExpired(candidate, renewedDueAt.plusSeconds(1))).isFalse();

        assertSameBotAndImmutableResult();
        assertThat(text("select lifecycle_status::text from bot.bots where id = ?", BOT_ID))
                .isEqualTo("STOPPING");
        assertThat(text("select stop_reason_code from bot.bots where id = ?", BOT_ID))
                .isEqualTo("CONTINUATION_DEADLINE_EXPIRED");
        assertStopCommand("CONTINUATION_DEADLINE_EXPIRED");
        assertThat(count("select count(*) from operations.audit_events where target_id = ? "
                + "and action_type = 'BOT_CONTINUATION_RENEWED'", BOT_ID)).isOne();
    }

    @Test
    void dispatchesTheEstablishedStopContractOnceAfterTheStopChoice() {
        choiceAdapter.updateOwned(
                ROOM_ID, PARTICIPATION_ID, OWNER_ID, PostEvaluationAction.STOP_AFTER_EVALUATION, CHOICE_AT);
        publishFinalResult();

        assertThat(stopTransition.transitionNext(TRANSITION_AT))
                .isEqualTo(PostEvaluationStopTransitionDecision.APPLIED);
        assertThat(stopTransition.transitionNext(TRANSITION_AT.plusSeconds(1)))
                .isEqualTo(PostEvaluationStopTransitionDecision.NO_READY_CANDIDATE);

        assertSameBotAndImmutableResult();
        assertThat(count("select count(*) from bot.continuation_deadlines where bot_id = ?", BOT_ID)).isZero();
        assertStopCommand("ROOM_EVALUATION_ENDED");
    }

    private void publishFinalResult() {
        jdbc.update(
                "update competition.live_evaluation_segments set end_event_sequence = 2, "
                        + "final_state_hash = 'sha256:final', source_set_hash = 'sha256:sources', "
                        + "virtual_liquidation_document = '{}'::jsonb, finalized_at = ? where id = ?",
                utc(CUTOFF), SEGMENT_ID);
        jdbc.update(
                "insert into performance.bot_snapshots "
                        + "(id, bot_id, snapshot_type, source_event_sequence, evaluated_at, equity_amount, "
                        + "total_return_pct, max_drawdown_pct, metrics_document, input_hash, "
                        + "calculation_rules_version, snapshot_hash, created_at) "
                        + "values (?, ?, 'LEADERBOARD_CUTOFF', 2, ?, 110000, 10, 2, '{}'::jsonb, ?, ?, ?, ?)",
                PERFORMANCE_SNAPSHOT_ID, BOT_ID, utc(CUTOFF), "sha256:input", "v1",
                "sha256:performance", utc(CUTOFF));
        jdbc.update("update competition.rooms set status = 'ENDED', ended_at = ? where id = ?", utc(ENDED_AT), ROOM_ID);
        jdbc.update(
                "update competition.participations set status = 'COMPLETED', evaluation_finished_at = ? where id = ?",
                utc(CUTOFF), PARTICIPATION_ID);
        var result = new FinalRoomResult(
                FINAL_SNAPSHOT_ID, ROOM_ID, TEMPLATE_ID, CUTOFF, RESULT_HASH, ENDED_AT,
                List.of(new FinalLeaderboardEntry(
                        PARTICIPATION_ID, PERFORMANCE_SNAPSHOT_ID, 1, false, BigDecimal.TEN,
                        "ELIGIBLE", null, "sha256:provenance", "{}", "{}")));
        assertThat(finalResultAdapter.save(result)).isEqualTo(FinalRoomResultWriteDecision.CREATED);
        assertThat(finalResultAdapter.save(result))
                .isEqualTo(FinalRoomResultWriteDecision.ALREADY_FINALIZED_IDENTICALLY);
    }

    private void assertSameBotAndImmutableResult() {
        assertThat(jdbc.queryForObject(
                "select bot_id from competition.participations where id = ?", UUID.class, PARTICIPATION_ID))
                .isEqualTo(BOT_ID);
        assertThat(text("select result_hash from competition.leaderboard_snapshots where id = ?", FINAL_SNAPSHOT_ID))
                .isEqualTo(RESULT_HASH);
        assertThat(count("select count(*) from competition.leaderboard_entries where snapshot_id = ?", FINAL_SNAPSHOT_ID))
                .isOne();
    }

    private void assertStopCommand(String reasonCode) {
        assertThat(count("select count(*) from operations.outbox_messages where aggregate_id = ? "
                + "and event_type = 'BOT_STOP_COMMAND'", BOT_ID)).isOne();
        assertThat(text("select payload_document->'metadata'->>'contractVersion' "
                + "from operations.outbox_messages where aggregate_id = ? and event_type = 'BOT_STOP_COMMAND'", BOT_ID))
                .isEqualTo("strategy-bot.v1");
        assertThat(text("select payload_document->>'botId' from operations.outbox_messages "
                + "where aggregate_id = ? and event_type = 'BOT_STOP_COMMAND'", BOT_ID))
                .isEqualTo(BOT_ID.toString());
        assertThat(text("select payload_document->>'expectedSnapshotHash' from operations.outbox_messages "
                + "where aggregate_id = ? and event_type = 'BOT_STOP_COMMAND'", BOT_ID))
                .isEqualTo("sha256:" + LAUNCH_HASH);
        assertThat(text("select payload_document->>'reasonCode' from operations.outbox_messages "
                + "where aggregate_id = ? and event_type = 'BOT_STOP_COMMAND'", BOT_ID))
                .isEqualTo(reasonCode);
    }

    private void seedEvaluation() {
        Instant publishedAt = CUTOFF.minus(Duration.ofDays(1));
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE'), (?, 'ACTIVE')",
                OWNER_ID, CREATOR_ID);
        jdbc.update(
                "insert into competition.scoring_template_versions "
                        + "(id, template_code, version, rules_document, rules_hash, published_at) "
                        + "values (?, 'TOTAL_RETURN', 'e90', '{}'::jsonb, 'sha256:e90-template', ?)",
                TEMPLATE_ID, utc(publishedAt));
        jdbc.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'E90', '1', 20, 'v1', 'sha256:e90-fee', ?, ?)",
                FEE_ID, utc(publishedAt), utc(publishedAt));
        jdbc.update(
                "insert into trading.buying_power_buffer_policy_versions "
                        + "(id, policy_code, version, buffer_bps, rounding_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'E90', '1', 0, 'v1', 'sha256:e90-buffer', ?, ?)",
                BUFFER_ID, utc(publishedAt), utc(publishedAt));
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, creator_account_id, name, access_type, "
                        + "status, created_at) values (?, 'LIVE_PAPER', 'USER', ?, 'E90 room', 'PUBLIC', "
                        + "'EVALUATING', ?)",
                ROOM_ID, CREATOR_ID, utc(publishedAt));
        jdbc.update(
                "insert into competition.room_rules "
                        + "(room_id, scoring_template_version_id, initial_cash_amount, bot_participation_limit, "
                        + "per_account_bot_limit, eligibility_document, market_scope_document, scoring_parameters, "
                        + "fee_policy_id, slippage_rate_bps, buying_power_buffer_policy_id, precision_rules_version, "
                        + "rules_hash, locked_at) values (?, ?, 100000, 10, 2, '{}'::jsonb, '{}'::jsonb, "
                        + "'{}'::jsonb, ?, 5, ?, 'v1', 'sha256:e90-rules', ?)",
                ROOM_ID, TEMPLATE_ID, FEE_ID, BUFFER_ID, utc(publishedAt));
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
                        + "execution_eligible_from, created_at, started_at, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', 'E90 bot', 'RUNNING', ?, ?, ?, ?, 0, ?)",
                BOT_ID, OWNER_ID, utc(publishedAt), utc(publishedAt), utc(publishedAt),
                utc(CUTOFF.minusSeconds(600)), utc(publishedAt));
        jdbc.update(
                "insert into bot.launch_snapshots "
                        + "(bot_id, snapshot_schema_version, semantic_snapshot, presentation_snapshot, "
                        + "semantic_hash, presentation_hash, snapshot_hash, created_at) "
                        + "values (?, 'basic-launch-snapshot.v1', '{}'::jsonb, '{}'::jsonb, "
                        + "'e90-semantic', 'e90-presentation', ?, ?)",
                BOT_ID, LAUNCH_HASH, utc(publishedAt));
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at, "
                        + "evaluation_started_at) values (?, ?, ?, ?, 'e90-bot', 'EVALUATING', ?, ?)",
                PARTICIPATION_ID, ROOM_ID, BOT_ID, OWNER_ID, utc(publishedAt), utc(CUTOFF.minusSeconds(600)));
        jdbc.update(
                "insert into competition.live_evaluation_segments "
                        + "(id, participation_id, segment_type, starts_at, ends_at, start_event_sequence, "
                        + "initial_state_hash) values (?, ?, 'OFFICIAL_EVALUATION', ?, ?, 1, 'sha256:initial')",
                SEGMENT_ID, PARTICIPATION_ID, utc(CUTOFF.minusSeconds(600)), utc(CUTOFF));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private Instant instant(String sql, Object... args) {
        return jdbc.queryForObject(sql, java.time.OffsetDateTime.class, args).toInstant();
    }

    private static java.time.OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("e9000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    private static final UUID OWNER_ID = id(1);
    private static final UUID CREATOR_ID = id(2);
    private static final UUID ROOM_ID = id(3);
    private static final UUID BOT_ID = id(4);
    private static final UUID PARTICIPATION_ID = id(5);
    private static final UUID TEMPLATE_ID = id(6);
    private static final UUID SEGMENT_ID = id(7);
    private static final UUID PERFORMANCE_SNAPSHOT_ID = id(8);
    private static final UUID FINAL_SNAPSHOT_ID = id(9);
    private static final UUID FEE_ID = id(10);
    private static final UUID BUFFER_ID = id(11);

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        PostEvaluationChoiceJooqAdapter.class,
        FinalRoomResultJooqAdapter.class,
        PrivateContinuationTransitionJooqAdapter.class,
        PostEvaluationStopTransitionJooqAdapter.class,
        BotContinuationJooqAdapter.class,
        ExpiredBotStopJooqQueryAdapter.class,
        BotStopCommandJooqAdapter.class
    })
    static class TestApplication {}
}

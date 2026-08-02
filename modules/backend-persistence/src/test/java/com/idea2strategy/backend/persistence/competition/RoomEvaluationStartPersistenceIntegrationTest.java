package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.competition.RoomEvaluationStartReport;
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
@SpringBootTest(classes = RoomEvaluationStartPersistenceIntegrationTest.TestApplication.class)
class RoomEvaluationStartPersistenceIntegrationTest {
    private static final UUID OWNER_ID = id(1);
    private static final UUID ROOM_ID = id(2);
    private static final UUID BOT_ID = id(3);
    private static final UUID PARTICIPATION_ID = id(4);
    private static final UUID SCORING_ID = id(5);
    private static final UUID FEE_ID = id(6);
    private static final UUID BUFFER_ID = id(7);
    private static final UUID OPERATOR_ID = id(8);
    private static final Instant EVALUATION_START = Instant.parse("2026-08-02T04:00:00Z");
    private static final Instant OBSERVED_AT = EVALUATION_START.plusSeconds(15);

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
    private RoomEvaluationStartJooqAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareReferences() {
        jdbc.update("delete from operations.outbox_messages");
        jdbc.update("delete from trading.ledger_entries");
        jdbc.update("delete from trading.ledger_transactions");
        jdbc.update("delete from trading.ledger_accounts");
        jdbc.update("delete from competition.participation_events");
        jdbc.update("delete from competition.participations");
        jdbc.update("delete from bot.bot_events");
        jdbc.update("delete from competition.room_schedules");
        jdbc.update("delete from competition.live_room_rules");
        jdbc.update("delete from competition.room_rules");
        jdbc.update("delete from competition.rooms");
        jdbc.update("delete from bot.launch_configurations");
        jdbc.update("delete from bot.launch_snapshots");
        jdbc.update("delete from bot.bots");
        jdbc.update("delete from competition.scoring_template_versions where id = ?", SCORING_ID);
        jdbc.update("delete from trading.fee_policy_versions where id = ?", FEE_ID);
        jdbc.update("delete from trading.buying_power_buffer_policy_versions where id = ?", BUFFER_ID);
        jdbc.update("delete from operations.operator_accounts where id = ?", OPERATOR_ID);
        jdbc.update("delete from identity.accounts where id = ?", OWNER_ID);
        var at = EVALUATION_START.atOffset(ZoneOffset.UTC);
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", OWNER_ID);
        jdbc.update(
                "insert into operations.operator_accounts "
                        + "(id, external_identity_key_hmac, status, mfa_enrolled_at, created_at) "
                        + "values (?, 'operator-e11', 'ACTIVE', ?, ?)",
                OPERATOR_ID, at.minusDays(2), at.minusDays(2));
        jdbc.update(
                "insert into competition.scoring_template_versions "
                        + "(id, template_code, version, rules_document, rules_hash, published_at) "
                        + "values (?, 'TOTAL_RETURN', 'v1', '{}'::jsonb, 'scoring-e11', ?)",
                SCORING_ID, at.minusDays(2));
        jdbc.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'DEFAULT', 'v1', 20, 'v1', 'fee-e11', ?, ?)",
                FEE_ID, at.minusDays(2), at.minusDays(2));
        jdbc.update(
                "insert into trading.buying_power_buffer_policy_versions "
                        + "(id, policy_code, version, buffer_bps, rounding_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'DEFAULT', 'v1', 0, 'v1', 'buffer-e11', ?, ?)",
                BUFFER_ID, at.minusDays(2), at.minusDays(2));
    }

    @Test
    void initializesOfficialStateAndEmitsOneStartCommand() {
        seedLiveParticipation();

        assertThat(adapter.startEligible(OBSERVED_AT, 10))
                .isEqualTo(new RoomEvaluationStartReport(OBSERVED_AT, 1));
        assertThat(adapter.startEligible(OBSERVED_AT.plusSeconds(5), 10))
                .isEqualTo(new RoomEvaluationStartReport(OBSERVED_AT.plusSeconds(5), 0));

        assertThat(jdbc.queryForObject(
                        "select status::text from competition.participations where id = ?",
                        String.class, PARTICIPATION_ID))
                .isEqualTo("EVALUATING");
        assertThat(jdbc.queryForObject(
                        "select evaluation_started_at from competition.participations where id = ?",
                        java.time.OffsetDateTime.class, PARTICIPATION_ID).toInstant())
                .isEqualTo(EVALUATION_START);
        assertThat(jdbc.queryForObject(
                        "select started_at is null from bot.bots where id = ?",
                        Boolean.class, BOT_ID))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select count(*) from trading.ledger_accounts where bot_id = ?",
                        Integer.class, BOT_ID))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from trading.ledger_transactions where bot_id = ?",
                        Integer.class, BOT_ID))
                .isEqualTo(1);
        assertThat(jdbc.queryForList(
                        "select direction::text as direction, amount from trading.ledger_entries "
                                + "where bot_id = ? order by direction",
                        BOT_ID))
                .extracting(row -> row.get("direction"))
                .containsExactly("CREDIT", "DEBIT");
        assertThat(jdbc.queryForObject(
                        "select count(*) from competition.participation_events where participation_id = ?",
                        Integer.class, PARTICIPATION_ID))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from bot.bot_events where bot_id = ?",
                        Integer.class, BOT_ID))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from operations.outbox_messages "
                                + "where aggregate_id = ? and event_type = 'ROOM_EVALUATION_START_COMMAND'",
                        Integer.class, PARTICIPATION_ID))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select payload_document ->> 'executionEligibleFrom' from operations.outbox_messages "
                                + "where aggregate_id = ?",
                        String.class, PARTICIPATION_ID))
                .isEqualTo(EVALUATION_START.toString());
    }

    @Test
    void concurrentWorkersStartOneParticipationOnce() throws Exception {
        seedLiveParticipation();
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<RoomEvaluationStartReport> first = executor.submit(() -> {
                gate.await();
                return adapter.startEligible(OBSERVED_AT, 10);
            });
            Future<RoomEvaluationStartReport> second = executor.submit(() -> {
                gate.await();
                return adapter.startEligible(OBSERVED_AT, 10);
            });
            gate.countDown();
            assertThat(List.of(first.get(), second.get()))
                    .extracting(RoomEvaluationStartReport::participantsStarted)
                    .containsExactlyInAnyOrder(1, 0);
        }
    }

    @Test
    void startsLateBacktestSubmissionFromItsActualAdmissionTime() {
        Instant admittedAt = EVALUATION_START.plusSeconds(5);
        seedBacktestParticipation(admittedAt);

        assertThat(adapter.startEligible(OBSERVED_AT, 10).participantsStarted()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select payload_document ->> 'executionEligibleFrom' from operations.outbox_messages "
                                + "where aggregate_id = ?",
                        String.class, PARTICIPATION_ID))
                .isEqualTo(admittedAt.toString());
        assertThat(jdbc.queryForObject(
                        "select evaluation_started_at from competition.participations where id = ?",
                        java.time.OffsetDateTime.class, PARTICIPATION_ID).toInstant())
                .isEqualTo(admittedAt);
    }

    @Test
    void rejectsNonEmptyOfficialStateWithoutPartialInitialization() {
        seedLiveParticipation();
        jdbc.update(
                "insert into trading.ledger_accounts "
                        + "(id, bot_id, account_key, account_type, currency_code, created_at) "
                        + "values (?, ?, 'dirty:cash', 'CASH', 'USD', ?)",
                id(50), BOT_ID, EVALUATION_START.atOffset(ZoneOffset.UTC));

        assertThatThrownBy(() -> adapter.startEligible(OBSERVED_AT, 10))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("official state is not empty");

        assertThat(jdbc.queryForObject(
                        "select status::text from competition.participations where id = ?",
                        String.class, PARTICIPATION_ID))
                .isEqualTo("REGISTERED");
        assertThat(jdbc.queryForObject("select count(*) from operations.outbox_messages", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from trading.ledger_transactions", Integer.class))
                .isZero();
    }

    private void seedLiveParticipation() {
        var at = EVALUATION_START.atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, creator_account_id, name, access_type, status, created_at) "
                        + "values (?, 'LIVE_PAPER', 'USER', ?, 'E11 Room', 'PUBLIC', 'EVALUATING', ?)",
                ROOM_ID, OWNER_ID, at.minusDays(1));
        seedRoomRulesAndSchedule(at);
        jdbc.update(
                "insert into competition.live_room_rules "
                        + "(room_id, stopped_bot_slot_policy, minimum_operation_seconds, minimum_fill_count) "
                        + "values (?, 'COUNT_UNTIL_END', 0, 0)",
                ROOM_ID);
        seedBotAndParticipation(at);
    }

    private void seedBacktestParticipation(Instant admittedAt) {
        var at = EVALUATION_START.atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, created_by_operator_id, name, access_type, status, created_at) "
                        + "values (?, 'BACKTEST', 'PLATFORM', ?, 'E11 Backtest', 'PUBLIC', 'EVALUATING', ?)",
                ROOM_ID, OPERATOR_ID, at.minusDays(1));
        seedRoomRulesAndSchedule(at);
        jdbc.update(
                "update competition.room_schedules set participation_closes_at = ? where room_id = ?",
                at.plusHours(1), ROOM_ID);
        seedBotAndParticipation(admittedAt.atOffset(ZoneOffset.UTC));
        jdbc.update(
                "update competition.participations set joined_at = ? where id = ?",
                admittedAt.atOffset(ZoneOffset.UTC), PARTICIPATION_ID);
    }

    private void seedRoomRulesAndSchedule(java.time.OffsetDateTime at) {
        jdbc.update(
                "insert into competition.room_rules "
                        + "(room_id, scoring_template_version_id, initial_cash_amount, bot_participation_limit, "
                        + "per_account_bot_limit, eligibility_document, market_scope_document, scoring_parameters, "
                        + "fee_policy_id, slippage_rate_bps, buying_power_buffer_policy_id, precision_rules_version, "
                        + "rules_hash, locked_at) values (?, ?, 100000, 10, 2, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, "
                        + "?, 5, ?, 'v1', 'rules-e11', ?)",
                ROOM_ID, SCORING_ID, FEE_ID, BUFFER_ID, at.minusDays(1));
        jdbc.update(
                "insert into competition.room_schedules "
                        + "(room_id, recruitment_opens_at, participation_opens_at, evaluation_starts_at, "
                        + "participation_closes_at, evaluation_ends_at, finalization_deadline_at, timezone_name) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'UTC')",
                ROOM_ID, at.minusDays(1), at.minusHours(1), at, at, at.plusHours(2), at.plusHours(3));
    }

    private void seedBotAndParticipation(java.time.OffsetDateTime at) {
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, created_at, "
                        + "execution_eligible_from, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', 'E11 Bot', 'RUNNING', ?, ?, ?, 0, ?)",
                BOT_ID, OWNER_ID, at.minusHours(1), at.minusHours(1), at, at.minusHours(1));
        jdbc.update(
                "insert into bot.launch_snapshots "
                        + "(bot_id, snapshot_schema_version, semantic_snapshot, presentation_snapshot, semantic_hash, "
                        + "presentation_hash, snapshot_hash, created_at) "
                        + "values (?, 'basic-launch-snapshot.v1', '{}'::jsonb, '{}'::jsonb, 'semantic-e11', "
                        + "'presentation-e11', 'snapshot-e11', ?)",
                BOT_ID, at.minusHours(1));
        jdbc.update(
                "insert into bot.launch_configurations "
                        + "(bot_id, initial_cash_amount, currency_code, broker_rules_version, accounting_rules_version, "
                        + "precision_rules_version, fee_policy_id, slippage_rate_bps, buying_power_buffer_policy_id, "
                        + "candidate_conflict_policy, configuration_hash) "
                        + "values (?, 100000, 'USD', 'v1', 'v1', 'v1', ?, 5, ?, '{}'::jsonb, 'config-e11')",
                BOT_ID, FEE_ID, BUFFER_ID);
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at) "
                        + "values (?, ?, ?, ?, 'e11-bot', 'REGISTERED', ?)",
                PARTICIPATION_ID, ROOM_ID, BOT_ID, OWNER_ID, at.minusHours(1));
    }

    private static UUID id(int suffix) {
        return UUID.fromString("86000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(RoomEvaluationStartJooqAdapter.class)
    static class TestApplication {}
}

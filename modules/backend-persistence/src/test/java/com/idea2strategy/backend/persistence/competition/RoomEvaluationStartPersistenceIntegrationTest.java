package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.idea2strategy.backend.application.competition.RoomEvaluationStartReport;
import com.idea2strategy.backend.messaging.competition.contract.RoomEvaluationCommandFixture;
import com.idea2strategy.backend.messaging.competition.contract.RoomEvaluationCommandType;
import java.nio.charset.StandardCharsets;
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
    private static final UUID SECOND_BOT_ID = id(9);
    private static final UUID SECOND_PARTICIPATION_ID = id(10);
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

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void prepareReferences() {
        jdbc.update("delete from operations.outbox_messages");
        jdbc.update("delete from trading.ledger_entries");
        jdbc.update("delete from trading.ledger_transactions");
        jdbc.update("delete from trading.ledger_accounts");
        jdbc.update("delete from competition.participation_events");
        jdbc.update("delete from competition.live_evaluation_segments");
        jdbc.update("delete from competition.participations");
        jdbc.update("delete from competition.backtest_evaluation_plans");
        jdbc.update("delete from bot.bot_events");
        jdbc.update("delete from competition.room_schedules");
        jdbc.update("delete from competition.live_room_rules");
        jdbc.update("delete from competition.room_rules");
        jdbc.update("delete from competition.rooms");
        jdbc.update("delete from bot.launch_configurations");
        jdbc.update("delete from bot.launch_contract_plans");
        jdbc.update("delete from bot.launch_snapshots");
        jdbc.update("delete from bot.bots");
        jdbc.update("delete from competition.scoring_template_versions where id = ?", SCORING_ID);
        jdbc.update("delete from trading.fee_policy_versions where id = ?", FEE_ID);
        jdbc.update("delete from trading.buying_power_buffer_policy_versions where id = ?", BUFFER_ID);
        jdbc.update("delete from operations.operator_accounts where id = ?", OPERATOR_ID);
        jdbc.update("truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
        jdbc.update("delete from identity.accounts where id = ?", OWNER_ID);
        var at = EVALUATION_START.atOffset(ZoneOffset.UTC);
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", OWNER_ID);
        jdbc.update(
                "insert into operations.operator_accounts "
                        + "(id, external_identity_key_hmac, external_identity_key_version, status, mfa_enrolled_at, created_at) "
                        + "values (?, 'operator-e11', 1, 'ACTIVE', ?, ?)",
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
    void initializesOfficialStateAndEmitsOneContractCompatibleStartCommand() throws Exception {
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
                        "select payload_document ->> 'effectiveAt' from operations.outbox_messages "
                                + "where aggregate_id = ? and event_type = 'ROOM_EVALUATION_START_COMMAND'",
                        String.class, PARTICIPATION_ID))
                .isEqualTo(EVALUATION_START.toString());

        UUID expectedSegmentId = UUID.nameUUIDFromBytes(
                ("live-evaluation-segment.v1:" + PARTICIPATION_ID).getBytes(StandardCharsets.UTF_8));
        assertThat(jdbc.queryForMap(
                        "select id, segment_type, starts_at, ends_at, start_event_sequence, initial_state_hash "
                                + "from competition.live_evaluation_segments where participation_id = ?",
                        PARTICIPATION_ID))
                .satisfies(segment -> {
                    assertThat(segment.get("id")).isEqualTo(expectedSegmentId);
                    assertThat(segment.get("segment_type")).isEqualTo("OFFICIAL_EVALUATION");
                    assertThat(((java.sql.Timestamp) segment.get("starts_at")).toInstant())
                            .isEqualTo(EVALUATION_START);
                    assertThat(((java.sql.Timestamp) segment.get("ends_at")).toInstant())
                            .isEqualTo(EVALUATION_START.plusSeconds(2 * 60 * 60));
                    assertThat(((Number) segment.get("start_event_sequence")).longValue()).isEqualTo(1L);
                    assertThat(segment.get("initial_state_hash"))
                            .asString()
                            .matches("sha256:[0-9a-f]{64}");
                });
        String payload = jdbc.queryForObject(
                "select payload_document::text from operations.outbox_messages "
                        + "where aggregate_id = ? and event_type = 'ROOM_EVALUATION_START_COMMAND'",
                String.class, PARTICIPATION_ID);
        RoomEvaluationCommandFixture command = objectMapper.readValue(payload, RoomEvaluationCommandFixture.class);
        UUID expectedCommandId = UUID.nameUUIDFromBytes(
                ("room-evaluation-start-command.v1:" + PARTICIPATION_ID)
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(command.contractVersion()).isEqualTo("room-performance.v1");
        assertThat(command.commandId()).isEqualTo(expectedCommandId);
        assertThat(command.type()).isEqualTo(RoomEvaluationCommandType.START_EVALUATION);
        assertThat(command.roomId()).isEqualTo(ROOM_ID);
        assertThat(command.participationId()).isEqualTo(PARTICIPATION_ID);
        assertThat(command.botId()).isEqualTo(BOT_ID);
        assertThat(command.evaluationSegmentId()).isEqualTo(expectedSegmentId);
        assertThat(command.scheduleVersion()).isEqualTo("room-schedule.v1");
        assertThat(command.evaluationStartsAt()).isEqualTo(EVALUATION_START);
        assertThat(command.evaluationEndsAt()).isEqualTo(EVALUATION_START.plusSeconds(2 * 60 * 60));
        assertThat(command.effectiveAt()).isEqualTo(EVALUATION_START);
        assertThat(command.idempotencyKey()).matches("sha256:[0-9a-f]{64}");
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(command)))
                .isEqualTo(objectMapper.readTree(payload));
        assertThat(jdbc.queryForMap(
                        "select event_schema_version, idempotency_key from operations.outbox_messages "
                                + "where aggregate_id = ?",
                        PARTICIPATION_ID))
                .containsEntry("event_schema_version", "room-performance.v1")
                .containsEntry("idempotency_key", command.idempotencyKey());
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
        assertThat(jdbc.queryForObject(
                        "select count(*) from competition.live_evaluation_segments where participation_id = ?",
                        Integer.class, PARTICIPATION_ID))
                .isEqualTo(1);
    }

    @Test
    void recordsOneCommonLiveInputHashForEveryBotAndBindsItIntoBotSpecificInitialState() {
        seedLiveParticipation();
        seedBotAndParticipation(
                SECOND_BOT_ID, SECOND_PARTICIPATION_ID,
                EVALUATION_START.atOffset(ZoneOffset.UTC));

        assertThat(adapter.startEligible(OBSERVED_AT, 10).participantsStarted()).isEqualTo(2);

        assertThat(jdbc.queryForList(
                        "select payload_document ->> 'liveEvaluationInputVersion' as version, "
                                + "payload_document ->> 'liveEvaluationInputHash' as input_hash "
                                + "from competition.participation_events "
                                + "where event_type = 'EVALUATION_STARTED' order by participation_id"))
                .hasSize(2)
                .allSatisfy(event -> {
                    assertThat(event.get("version")).isEqualTo("live-evaluation-input.v1");
                    assertThat(event.get("input_hash")).asString().matches("sha256:[0-9a-f]{64}");
                })
                .extracting(event -> event.get("input_hash"))
                .containsOnly(jdbc.queryForObject(
                        "select payload_document ->> 'liveEvaluationInputHash' "
                                + "from competition.participation_events where participation_id = ?",
                        String.class, PARTICIPATION_ID));
        assertThat(jdbc.queryForList(
                        "select initial_state_hash from competition.live_evaluation_segments order by participation_id",
                        String.class))
                .hasSize(2)
                .doesNotHaveDuplicates();
    }

    @Test
    void rejectsAChangedLockedInputBeforeStartingAnotherBotAndRollsBackThatAttempt() {
        seedLiveParticipation();

        assertThat(adapter.startEligible(OBSERVED_AT, 1).participantsStarted()).isEqualTo(1);
        String lockedHash = jdbc.queryForObject(
                "select payload_document ->> 'liveEvaluationInputHash' "
                        + "from competition.participation_events where participation_id = ?",
                String.class, PARTICIPATION_ID);
        seedBotAndParticipation(
                SECOND_BOT_ID, SECOND_PARTICIPATION_ID,
                EVALUATION_START.atOffset(ZoneOffset.UTC));
        jdbc.update(
                "update competition.room_rules set scoring_parameters = '{\"weight\":2}'::jsonb, "
                        + "rules_hash = 'rules-e17-changed' where room_id = ?",
                ROOM_ID);

        assertThatThrownBy(() -> adapter.startEligible(OBSERVED_AT.plusSeconds(1), 10))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("live evaluation input does not match");

        assertThat(jdbc.queryForObject(
                        "select status::text from competition.participations where id = ?",
                        String.class, SECOND_PARTICIPATION_ID))
                .isEqualTo("REGISTERED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from competition.live_evaluation_segments where participation_id = ?",
                        Integer.class, SECOND_PARTICIPATION_ID))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select payload_document ->> 'liveEvaluationInputHash' "
                                + "from competition.participation_events where participation_id = ?",
                        String.class, PARTICIPATION_ID))
                .isEqualTo(lockedHash);
    }

    @Test
    void startsLateBacktestSubmissionFromItsActualAdmissionTime() throws Exception {
        Instant admittedAt = EVALUATION_START.plusSeconds(5);
        seedBacktestParticipation(admittedAt);

        assertThat(adapter.startEligible(OBSERVED_AT, 10).participantsStarted()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select payload_document ->> 'effectiveAt' from operations.outbox_messages "
                                + "where aggregate_id = ? and event_type = 'ROOM_EVALUATION_START_COMMAND'",
                        String.class, PARTICIPATION_ID))
                .isEqualTo(admittedAt.toString());
        assertThat(jdbc.queryForObject(
                        "select evaluation_started_at from competition.participations where id = ?",
                        java.time.OffsetDateTime.class, PARTICIPATION_ID).toInstant())
                .isEqualTo(admittedAt);
        assertThat(jdbc.queryForObject(
                        "select count(*) from competition.live_evaluation_segments where participation_id = ?",
                        Integer.class, PARTICIPATION_ID))
                .isZero();
        String payload = jdbc.queryForObject(
                "select payload_document::text from operations.outbox_messages "
                        + "where aggregate_id = ? and event_type = 'ROOM_EVALUATION_START_COMMAND'",
                String.class, PARTICIPATION_ID);
        RoomEvaluationCommandFixture command = objectMapper.readValue(payload, RoomEvaluationCommandFixture.class);
        assertThat(command.type()).isEqualTo(RoomEvaluationCommandType.START_EVALUATION);
        assertThat(command.evaluationSegmentId()).isEqualTo(UUID.nameUUIDFromBytes(
                ("backtest-evaluation-segment.v1:" + PARTICIPATION_ID)
                        .getBytes(StandardCharsets.UTF_8)));
        assertThat(command.evaluationStartsAt()).isEqualTo(EVALUATION_START);
        assertThat(command.evaluationEndsAt()).isEqualTo(EVALUATION_START.plusSeconds(2 * 60 * 60));
        assertThat(command.effectiveAt()).isEqualTo(admittedAt);
        assertThat(jdbc.queryForMap(
                        "select event_schema_version, payload_document ->> 'requestReason' as request_reason, "
                                + "payload_document ->> 'roomId' as room_id, "
                                + "payload_document ->> 'participationId' as participation_id "
                                + "from operations.outbox_messages "
                                + "where aggregate_id = ? and event_type = 'COMPETITION_BACKTEST_REQUESTED'",
                        PARTICIPATION_ID))
                .containsEntry("event_schema_version", "backtest-request.v1")
                .containsEntry("request_reason", "COMPETITION_EVALUATION")
                .containsEntry("room_id", ROOM_ID.toString())
                .containsEntry("participation_id", PARTICIPATION_ID.toString());
        assertThat(jdbc.queryForObject(
                        "select jsonb_exists(payload_document, 'liveEvaluationInputHash') "
                                + "from competition.participation_events where participation_id = ?",
                        Boolean.class, PARTICIPATION_ID))
                .isFalse();
    }

    @Test
    void rejectsAnEmptyLiveEvaluationWindowWithoutCreatingOfficialState() {
        seedLiveParticipation();
        jdbc.update(
                "update competition.room_schedules set evaluation_ends_at = ?, "
                        + "finalization_deadline_at = ? where room_id = ?",
                EVALUATION_START.atOffset(ZoneOffset.UTC),
                EVALUATION_START.plusSeconds(60).atOffset(ZoneOffset.UTC),
                ROOM_ID);

        assertThatThrownBy(() -> adapter.startEligible(OBSERVED_AT, 10))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("non-empty schedule window");

        assertThat(jdbc.queryForObject(
                        "select status::text from competition.participations where id = ?",
                        String.class, PARTICIPATION_ID))
                .isEqualTo("REGISTERED");
        assertThat(jdbc.queryForObject("select count(*) from competition.live_evaluation_segments", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from bot.bot_events", Integer.class))
                .isZero();
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
        assertThat(jdbc.queryForObject("select count(*) from competition.live_evaluation_segments", Integer.class))
                .isZero();
    }

    @Test
    void rollsBackLiveSegmentAndOfficialStateWhenStartCommandCannotBeRecorded() {
        seedLiveParticipation();
        UUID outboxId = UUID.nameUUIDFromBytes(
                ("outbox-message:" + PARTICIPATION_ID).getBytes(StandardCharsets.UTF_8));
        jdbc.update(
                "insert into operations.outbox_messages "
                        + "(id, owner_domain, aggregate_id, aggregate_sequence, event_type, "
                        + "event_schema_version, payload_document, idempotency_key, created_at) "
                        + "values (?, 'competition', ?, 1, 'EXISTING', 'test.v1', '{}'::jsonb, ?, ?)",
                outboxId, id(90), "existing:" + PARTICIPATION_ID, OBSERVED_AT.atOffset(ZoneOffset.UTC));

        assertThatThrownBy(() -> adapter.startEligible(OBSERVED_AT, 10))
                .hasStackTraceContaining("duplicate key");

        assertThat(jdbc.queryForObject(
                        "select status::text from competition.participations where id = ?",
                        String.class, PARTICIPATION_ID))
                .isEqualTo("REGISTERED");
        assertThat(jdbc.queryForObject("select count(*) from competition.live_evaluation_segments", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from trading.ledger_transactions", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from bot.bot_events", Integer.class))
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
                "insert into bot.launch_contract_plans "
                        + "(bot_id, contract_version, plan_schema_version, plan_checksum, plan_document, created_at) "
                        + "values (?, 'strategy-bot.v1', 'basic-compiled-plan.v1', ?, '{}'::jsonb, ?)",
                BOT_ID, "sha256:" + "2".repeat(64), at.minusHours(1));
        jdbc.update(
                "insert into competition.backtest_evaluation_plans "
                        + "(room_id, plan_version, period_count, plan_hash, commitment_hash, "
                        + "commitment_nonce_ciphertext, nonce_key_version, locked_at) "
                        + "values (?, 'competition-plan.v1', 2, ?, ?, 'ciphertext', 1, ?)",
                ROOM_ID, "sha256:" + "3".repeat(64), "sha256:" + "4".repeat(64), at.minusDays(1));
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
        seedBotAndParticipation(BOT_ID, PARTICIPATION_ID, at);
    }

    private void seedBotAndParticipation(
            UUID botId, UUID participationId, java.time.OffsetDateTime at) {
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, created_at, "
                        + "execution_eligible_from, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', 'E11 Bot', 'RUNNING', ?, ?, ?, 0, ?)",
                botId, OWNER_ID, at.minusHours(1), at.minusHours(1), at, at.minusHours(1));
        jdbc.update(
                "insert into bot.launch_snapshots "
                        + "(bot_id, snapshot_schema_version, semantic_snapshot, presentation_snapshot, semantic_hash, "
                        + "presentation_hash, snapshot_hash, created_at) "
                        + "values (?, 'basic-launch-snapshot.v1', '{}'::jsonb, '{}'::jsonb, ?, ?, ?, ?)",
                botId, "1".repeat(64), "2".repeat(64), "3".repeat(64), at.minusHours(1));
        jdbc.update(
                "insert into bot.launch_configurations "
                        + "(bot_id, initial_cash_amount, currency_code, broker_rules_version, accounting_rules_version, "
                        + "precision_rules_version, fee_policy_id, slippage_rate_bps, buying_power_buffer_policy_id, "
                        + "candidate_conflict_policy, configuration_hash) "
                        + "values (?, 100000, 'USD', 'v1', 'v1', 'v1', ?, 5, ?, '{}'::jsonb, 'config-e11')",
                botId, FEE_ID, BUFFER_ID);
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at) "
                        + "values (?, ?, ?, ?, ?, 'REGISTERED', ?)",
                participationId, ROOM_ID, botId, OWNER_ID, "e11-bot-" + participationId, at.minusHours(1));
    }

    private static UUID id(int suffix) {
        return UUID.fromString("86000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(RoomEvaluationStartJooqAdapter.class)
    static class TestApplication {}
}

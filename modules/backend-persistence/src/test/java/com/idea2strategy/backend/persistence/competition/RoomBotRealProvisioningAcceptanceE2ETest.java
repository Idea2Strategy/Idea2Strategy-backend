package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.botcontrol.BotRunDispatchMode;
import com.idea2strategy.backend.application.competition.CreateUserLiveRoomCommand;
import com.idea2strategy.backend.application.competition.FinalLeaderboardEntry;
import com.idea2strategy.backend.application.competition.FinalRoomResult;
import com.idea2strategy.backend.application.competition.FinalRoomResultWriteDecision;
import com.idea2strategy.backend.application.competition.PostEvaluationAction;
import com.idea2strategy.backend.application.competition.PrivateContinuationTransitionService;
import com.idea2strategy.backend.application.competition.RoomEvaluationStartService;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionService;
import com.idea2strategy.backend.application.competition.RoomScheduleTransitionService;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogService;
import com.idea2strategy.backend.application.competition.UserCompetitionRoomCreationService;
import com.idea2strategy.backend.application.competition.UserPostEvaluationChoiceService;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseRejectedException;
import com.idea2strategy.backend.domain.competition.RoomAccessType;
import com.idea2strategy.backend.domain.competition.RoomSchedule;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease;
import com.idea2strategy.backend.persistence.botcontrol.BotRunCommandJooqAdapter;
import com.idea2strategy.backend.persistence.backtest.FeatureMaterializationPinResolver;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/**
 * E90/B92 acceptance: the room bot is B's real provisioned bot, not a seeded row.
 *
 * <p>{@link CompetitionJourneyPersistenceE2ETest} proves E's journey over bots its own helper
 * inserts, which leaves the B boundary unexercised — exactly the gap E90 records. Here the
 * provisioning action handed to E's admission is B's production
 * {@link RoomStrategyBotProvisioningJooqAdapter} building the bot from a validated immutable
 * release, and the run command is B's production {@link BotRunCommandJooqAdapter}. What this
 * journey owes E90:
 *
 * <ul>
 *   <li>admission provisions through B from a real validated release, atomically with the
 *       participation (a stale validation aborts both),
 *   <li>B's RUN command on the room bot is pinned to the room's evaluation start
 *       ({@code WAITING}), carries the locked snapshot hash, dispatches once under redelivery, and
 *       creates no 30-day deadline while the participation is active,
 *   <li>E's evaluation start touches the provisioned bot exactly once,
 *   <li>after the continue-private choice the very same bot survives the room: same id, same
 *       immutable launch snapshot, a 30-day deadline anchored to the room's end, and the frozen
 *       official result unchanged by the bot's later life.
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = RoomBotRealProvisioningAcceptanceE2ETest.TestApplication.class)
class RoomBotRealProvisioningAcceptanceE2ETest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-03T00:00:00Z");
    private static final Instant RECRUITMENT_AT = CREATED_AT.plusSeconds(10);
    private static final Instant ADMISSION_AT = CREATED_AT.plusSeconds(30);
    private static final Instant EVALUATION_AT = CREATED_AT.plusSeconds(60);
    private static final Instant CHOICE_AT = CREATED_AT.plusSeconds(90);
    private static final Instant CUTOFF_AT = CREATED_AT.plusSeconds(120);
    private static final Instant FINALIZED_AT = CUTOFF_AT.plusSeconds(10);

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);
    private static final String LAUNCH_HASH = "d".repeat(64);
    private static final String RESULT_HASH = "sha256:e90-acceptance-result";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired CompetitionRoomJpaCommandAdapter roomCommands;
    @Autowired ScoringTemplateCatalogJooqQueryAdapter scoringCatalogQueries;
    @Autowired RoomScheduleTransitionJooqAdapter scheduleTransitions;
    @Autowired RoomParticipationAdmissionJooqAdapter admissions;
    @Autowired RoomEvaluationStartJooqAdapter evaluationStarts;
    @Autowired PostEvaluationChoiceJooqAdapter choices;
    @Autowired FinalRoomResultJooqAdapter finalResults;
    @Autowired PrivateContinuationTransitionJooqAdapter continuations;
    @Autowired RoomStrategyBotProvisioningJooqAdapter roomProvisioning;
    @Autowired BotRunCommandJooqAdapter runCommands;
    @Autowired JdbcTemplate jdbc;

    @Test
    void carriesBsProvisionedBotFromAdmissionThroughEvaluationIntoPrivateContinuation() {
        seedReferences();
        createRoom();
        new RoomScheduleTransitionService(scheduleTransitions, fixed(RECRUITMENT_AT)).run(10);

        // A validation that no longer matches aborts the admission and the participation with it.
        assertThatThrownBy(() -> admissionService(OWNER_ID, STALE_PARTICIPATION_ID, STALE_EVENT_ID)
                        .admit(ROOM_ID, "e90-stale", context -> roomProvisioning.provision(
                                release(BOT_ID, OWNER_ID, context.launchRules()), VALIDATION_RUN_ID, 8, HASH_A,
                                context.executionEligibleFrom())))
                .isInstanceOf(ImmutableStrategyReleaseRejectedException.class);
        assertThat(count("select count(*) from bot.bots where id = ?", BOT_ID)).isZero();
        assertThat(count("select count(*) from competition.participations where id = ?", STALE_PARTICIPATION_ID))
                .isZero();

        // B's real provisioning, inside E's admission transaction, building the launch
        // configuration from the room's locked rules the context hands over — twice, because a
        // room below two participants auto-ends at participation close (E31) and never evaluates.
        var admitted = admissionService(OWNER_ID, PARTICIPATION_ID, EVENT_ID)
                .admit(ROOM_ID, "e90-real-bot", context -> roomProvisioning.provision(
                        release(BOT_ID, OWNER_ID, context.launchRules()), VALIDATION_RUN_ID, 7, HASH_A,
                        context.executionEligibleFrom()));
        admissionService(PEER_OWNER_ID, PEER_PARTICIPATION_ID, PEER_EVENT_ID)
                .admit(ROOM_ID, "e90-peer-bot", context -> roomProvisioning.provision(
                        release(PEER_BOT_ID, PEER_OWNER_ID, context.launchRules()), PEER_VALIDATION_RUN_ID,
                        7, HASH_A, context.executionEligibleFrom()));
        assertThat(admitted.botId()).isEqualTo(BOT_ID);
        assertThat(instant("select execution_eligible_from from bot.bots where id = ?", BOT_ID))
                .isEqualTo(EVALUATION_AT);
        assertThat(jdbc.queryForObject(
                        "select started_at is null from bot.bots where id = ?", Boolean.class, BOT_ID))
                .isTrue();
        assertThat(text("select snapshot_hash from bot.launch_snapshots where bot_id = ?", BOT_ID))
                .isEqualTo(LAUNCH_HASH);

        // B's RUN command before the evaluation window: WAITING, pinned to the room schedule,
        // locked snapshot in the payload, once under redelivery, and no 30-day deadline while the
        // participation is active.
        var dispatch = runCommands.issueOwned(BOT_ID, OWNER_ID, ADMISSION_AT.plusSeconds(5)).orElseThrow();
        assertThat(dispatch.mode()).isEqualTo(BotRunDispatchMode.WAITING);
        assertThat(dispatch.executionEligibleFrom()).isEqualTo(EVALUATION_AT);
        assertThat(dispatch.created()).isTrue();
        var redelivered = runCommands.issueOwned(BOT_ID, OWNER_ID, ADMISSION_AT.plusSeconds(6)).orElseThrow();
        assertThat(redelivered.created()).isFalse();
        assertThat(runCommandCount()).isOne();
        assertThat(text("select payload_document->>'expectedSnapshotHash' from operations.outbox_messages "
                        + "where aggregate_id = ? and event_type = 'BOT_RUN_COMMAND'", BOT_ID))
                .isEqualTo("sha256:" + LAUNCH_HASH);
        assertThat(count("select count(*) from bot.continuation_deadlines where bot_id = ?", BOT_ID))
                .isZero();

        // E starts the evaluation on the provisioned bots exactly once.
        new RoomScheduleTransitionService(scheduleTransitions, fixed(EVALUATION_AT)).run(10);
        assertThat(new RoomEvaluationStartService(evaluationStarts, fixed(EVALUATION_AT)).run(10)
                        .participantsStarted())
                .isEqualTo(2);
        assertThat(new RoomEvaluationStartService(evaluationStarts, fixed(EVALUATION_AT)).run(10)
                        .participantsStarted())
                .isZero();
        // The full journey substitutes F's separately tested matching OPENED facts.
        jdbc.update("update competition.live_evaluation_segments set start_event_sequence = 1, "
                + "initial_state_hash = 'sha256:' || repeat('1', 64) where start_event_sequence is null");
        jdbc.update("update competition.participations set status = 'EVALUATING', evaluation_started_at = ? "
                + "where room_id = ? and status = 'PENDING_LEDGER'",
                EVALUATION_AT.atOffset(ZoneOffset.UTC), ROOM_ID);
        assertThat(count("select count(*) from competition.live_evaluation_segments "
                        + "where participation_id = ?", PARTICIPATION_ID))
                .isOne();

        // Choice, end, official result.
        new UserPostEvaluationChoiceService(choices, () -> OWNER_ID, fixed(CHOICE_AT))
                .update(ROOM_ID, PARTICIPATION_ID, PostEvaluationAction.CONTINUE_PRIVATE);
        new UserPostEvaluationChoiceService(choices, () -> PEER_OWNER_ID, fixed(CHOICE_AT))
                .update(ROOM_ID, PEER_PARTICIPATION_ID, PostEvaluationAction.STOP_AFTER_EVALUATION);
        assertThat(new RoomScheduleTransitionService(scheduleTransitions, fixed(CUTOFF_AT)).run(10)
                        .transitionsApplied())
                .isPositive();
        finalizeWithFakeTradingResult();

        // The continuation keeps the very same bot, anchors its deadline to the room's end, and
        // repeated runs converge.
        var continuation = new PrivateContinuationTransitionService(continuations, fixed(FINALIZED_AT));
        assertThat(continuation.run(10).transitionsApplied()).isOne();
        assertThat(continuation.run(10).transitionsApplied()).isZero();

        Instant endedAt = instant("select ended_at from competition.rooms where id = ?", ROOM_ID);
        assertThat(jdbc.queryForObject(
                        "select bot_id from competition.participations where id = ?", UUID.class, PARTICIPATION_ID))
                .isEqualTo(BOT_ID);
        assertThat(instant("select due_at from bot.continuation_deadlines where bot_id = ?", BOT_ID))
                .isEqualTo(endedAt.plus(Duration.ofDays(30)));
        assertThat(text("select snapshot_hash from bot.launch_snapshots where bot_id = ?", BOT_ID))
                .isEqualTo(LAUNCH_HASH);
        assertThat(text("select lifecycle_status::text from bot.bots where id = ?", BOT_ID))
                .isEqualTo("RUNNING");

        // A post-room RUN resolves as a personal bot: immediate, and a genuinely new command.
        //
        // It does not converge on the room's command, and must not (C93). The room's run opened a
        // window the room's schedule closed; this one opens an unbounded window that only the owner's
        // stop closes. Reusing the room's command would have the evaluation runtime stop the bot at the
        // room's end while its owner believes it is running.
        var afterRoom = runCommands.issueOwned(BOT_ID, OWNER_ID, FINALIZED_AT.plusSeconds(30)).orElseThrow();
        assertThat(afterRoom.mode()).isEqualTo(BotRunDispatchMode.IMMEDIATE);
        assertThat(afterRoom.created()).isTrue();
        assertThat(runCommandCount()).isEqualTo(2);
        assertThat(text(
                        "select payload_document ->> 'executionEligibleUntil' "
                                + "from operations.outbox_messages where id = ?",
                        afterRoom.messageId()))
                .as("a personal bot's window has no scheduled end")
                .isNull();

        // The frozen official result did not move with the bot's later life.
        assertThat(text("select result_hash from competition.leaderboard_snapshots where room_id = ?", ROOM_ID))
                .isEqualTo(RESULT_HASH);
    }

    private RoomParticipationAdmissionService admissionService(UUID ownerId, UUID participationId, UUID eventId) {
        return new RoomParticipationAdmissionService(
                admissions, () -> ownerId, fixed(ADMISSION_AT), () -> participationId, () -> eventId);
    }

    private void createRoom() {
        var catalog = new ScoringTemplateCatalogService(
                scoringCatalogQueries, fixed(CREATED_AT), new ObjectMapper());
        new UserCompetitionRoomCreationService(
                roomCommands, catalog, () -> CREATOR_ID, fixed(CREATED_AT), () -> ROOM_ID, new ObjectMapper())
                .create(new CreateUserLiveRoomCommand(
                        "E90 real provisioning room",
                        RoomAccessType.SECRET,
                        TEMPLATE_ID,
                        Map.of(),
                        new BigDecimal("100000.00000000"),
                        10,
                        2,
                        "COUNT_UNTIL_END",
                        0,
                        0,
                        FEE_ID,
                        BUFFER_ID,
                        new RoomSchedule(
                                RECRUITMENT_AT,
                                CREATED_AT.plusSeconds(20),
                                EVALUATION_AT,
                                CREATED_AT.plusSeconds(50),
                                CUTOFF_AT,
                                CUTOFF_AT.plusSeconds(60),
                                "UTC")));
    }

    /**
     * The validated release B provisions from — the same shape B16 locks, with the launch
     * configuration taken from the room's locked rules exactly as E09 requires (E's evaluation
     * start refuses a configuration that differs from the room).
     */
    private ImmutableStrategyRelease release(
            UUID botId, UUID ownerId,
            com.idea2strategy.backend.application.competition.RoomBotLaunchRules launchRules) {
        var configuration = new ImmutableStrategyRelease.LaunchConfiguration(
                launchRules.initialCashAmount(), "broker/v1", "accounting/v1",
                launchRules.precisionRulesVersion(), launchRules.feePolicyId(),
                launchRules.buyingPowerBufferPolicyId(), "{\"policy\":\"FIRST_WINS\"}", HASH_C);
        UUID flowId = UUID.nameUUIDFromBytes((botId + ":flow").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID partitionId = UUID.nameUUIDFromBytes(
                (botId + ":partition").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var flow = new ImmutableStrategyRelease.Flow(
                flowId, "buy", CATALOG_ID, PLAN_ID, "{\"key\":\"buy\"}", "{}", HASH_A, HASH_B,
                HASH_C, List.of(INSTRUMENT_ID),
                List.of(new ImmutableStrategyRelease.FeatureRequirement(INSTRUMENT_ID, FEATURE_ID)), 0);
        var partition = new ImmutableStrategyRelease.Partition(
                partitionId, "E90", null, 10_000, HASH_C, List.of(flow));
        return new ImmutableStrategyRelease(
                botId, ownerId, "E90", null, "{\"mode\":\"BASIC\"}", "{\"name\":\"E90\"}",
                HASH_A, HASH_B, LAUNCH_HASH, configuration, partition, contractPlan(), ADMISSION_AT);
    }

    private static ImmutableStrategyRelease.ContractPlan contractPlan() {
        return new ImmutableStrategyRelease.ContractPlan(
                "strategy-bot.v1", "basic-compiled-plan.v1", "sha256:" + "c".repeat(64),
                "{\"contractVersion\":\"strategy-bot.v1\"}");
    }

    private void seedReferences() {
        var at = utc(CREATED_AT);
        var before = utc(CREATED_AT.minusSeconds(1));
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) values "
                        + "(?, 'ACTIVE', ?), (?, 'ACTIVE', ?), (?, 'ACTIVE', ?)",
                CREATOR_ID, at, OWNER_ID, at, PEER_OWNER_ID, at);
        jdbc.update(
                "insert into competition.scoring_template_versions "
                        + "(id, template_code, version, rules_document, rules_hash, published_at) values "
                        + "(?, 'SINGLE_TOTAL_RETURN_V1', 'e90', ?::jsonb, 'sha256:e90-template', ?)",
                TEMPLATE_ID,
                "{\"kind\":\"SINGLE\",\"calculationRulesVersion\":\"1.0.0\","
                        + "\"components\":[{\"metric\":\"TOTAL_RETURN\","
                        + "\"direction\":\"HIGHER_IS_BETTER\",\"coefficient\":1}],"
                        + "\"adjustments\":[]}",
                before);
        jdbc.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'E90', '1', 20, 'v1', 'sha256:e90-fee', ?, ?)",
                FEE_ID, before, before);
        jdbc.update(
                "insert into trading.buying_power_buffer_policy_versions "
                        + "(id, policy_code, version, buffer_bps, rounding_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'E90', '1', 0, 'v1', 'sha256:e90-buffer', ?, ?)",
                BUFFER_ID, before, before);
        jdbc.update(
                "insert into strategy.element_catalog_versions "
                        + "(id, language_version, schema_version, catalog_version, data_requirement_version, "
                        + "definition_hash, published_at) values (?, 'basic/v1', 'schema/v1', 'catalog/v1', "
                        + "'data/v1', ?, ?)",
                CATALOG_ID, LAUNCH_HASH, at);
        jdbc.update(
                "insert into strategy.compiled_flow_plans "
                        + "(id, element_catalog_version_id, semantic_hash, compiler_version, "
                        + "required_feature_set_hash, plan_document, plan_hash, created_at) "
                        + "values (?, ?, ?, 'basic-compiler:1.0.0', ?, '{}'::jsonb, ?, ?)",
                PLAN_ID, CATALOG_ID, HASH_A, HASH_B, HASH_C, at);
        jdbc.update(
                "insert into market_data.instruments "
                        + "(id, asset_type, primary_exchange_mic, currency_code) values (?, 'STOCK', 'XNAS', 'USD')",
                INSTRUMENT_ID);
        jdbc.update(
                "insert into market_data.feature_definitions "
                        + "(id, element_catalog_version_id, feature_code, calculator_version, resolution, "
                        + "normalized_parameters, output_value_type, required_history_points, definition_hash) "
                        + "values (?, ?, 'RSI_14', '1.0.0', '1m', '{}'::jsonb, 'NUMBER', 14, ?)",
                FEATURE_ID, CATALOG_ID, HASH_B);
        jdbc.update(
                "insert into strategy.strategies "
                        + "(id, owner_account_id, mode, name, edit_sequence, created_at, updated_at) "
                        + "values (?, ?, 'BASIC', 'E90', 7, ?, ?)",
                STRATEGY_ID, OWNER_ID, at, at);
        jdbc.update(
                "insert into strategy.strategy_documents "
                        + "(strategy_id, semantic_document, presentation_document, semantic_schema_version, "
                        + "presentation_schema_version, semantic_hash, presentation_hash, edit_sequence, "
                        + "created_at, updated_at) values (?, '{}'::jsonb, '{}'::jsonb, 'basic-semantic/v1', "
                        + "'basic-presentation/v1', ?, ?, 7, ?, ?)",
                STRATEGY_ID, HASH_A, HASH_B, at, at);
        jdbc.update(
                "insert into strategy.validation_runs "
                        + "(id, strategy_id, requested_by_account_id, requested_edit_sequence, semantic_hash, "
                        + "element_catalog_version_id, status, issue_count, result_document, requested_at, "
                        + "completed_at) values (?, ?, ?, 7, ?, ?, 'VALID', 0, '{}'::jsonb, ?, ?)",
                VALIDATION_RUN_ID, STRATEGY_ID, OWNER_ID, HASH_A, CATALOG_ID, at, at);
        jdbc.update(
                "insert into strategy.strategies "
                        + "(id, owner_account_id, mode, name, edit_sequence, created_at, updated_at) "
                        + "values (?, ?, 'BASIC', 'E90 peer', 7, ?, ?)",
                PEER_STRATEGY_ID, PEER_OWNER_ID, at, at);
        jdbc.update(
                "insert into strategy.strategy_documents "
                        + "(strategy_id, semantic_document, presentation_document, semantic_schema_version, "
                        + "presentation_schema_version, semantic_hash, presentation_hash, edit_sequence, "
                        + "created_at, updated_at) values (?, '{}'::jsonb, '{}'::jsonb, 'basic-semantic/v1', "
                        + "'basic-presentation/v1', ?, ?, 7, ?, ?)",
                PEER_STRATEGY_ID, HASH_A, HASH_B, at, at);
        jdbc.update(
                "insert into strategy.validation_runs "
                        + "(id, strategy_id, requested_by_account_id, requested_edit_sequence, semantic_hash, "
                        + "element_catalog_version_id, status, issue_count, result_document, requested_at, "
                        + "completed_at) values (?, ?, ?, 7, ?, ?, 'VALID', 0, '{}'::jsonb, ?, ?)",
                PEER_VALIDATION_RUN_ID, PEER_STRATEGY_ID, PEER_OWNER_ID, HASH_A, CATALOG_ID, at, at);
    }

    /**
     * The trading side of the result is faked: the boundary under test is B↔E, and F's producer
     * side has its own acceptance (E91, backend #135).
     */
    private void finalizeWithFakeTradingResult() {
        fakeTradingResult(PARTICIPATION_ID, BOT_ID, PERFORMANCE_ID);
        fakeTradingResult(PEER_PARTICIPATION_ID, PEER_BOT_ID, PEER_PERFORMANCE_ID);
        Instant endedAt = instant("select ended_at from competition.rooms where id = ?", ROOM_ID);
        var result = new FinalRoomResult(
                FINAL_SNAPSHOT_ID, ROOM_ID, TEMPLATE_ID, CUTOFF_AT, RESULT_HASH, endedAt,
                List.of(
                        new FinalLeaderboardEntry(
                                PARTICIPATION_ID, PERFORMANCE_ID, 1, false, BigDecimal.TEN,
                                "ELIGIBLE", null, "sha256:e90-provenance-1", "{}", "{}"),
                        new FinalLeaderboardEntry(
                                PEER_PARTICIPATION_ID, PEER_PERFORMANCE_ID, 2, false, BigDecimal.ONE,
                                "ELIGIBLE", null, "sha256:e90-provenance-2", "{}", "{}")));
        assertThat(finalResults.save(result)).isEqualTo(FinalRoomResultWriteDecision.CREATED);
        assertThat(finalResults.save(result))
                .isEqualTo(FinalRoomResultWriteDecision.ALREADY_FINALIZED_IDENTICALLY);
    }

    private void fakeTradingResult(UUID participationId, UUID botId, UUID performanceId) {
        jdbc.update(
                "update competition.live_evaluation_segments set end_event_sequence = 2, "
                        + "final_state_hash = ?, source_set_hash = ?, "
                        + "virtual_liquidation_document = '{}'::jsonb, finalized_at = ? "
                        + "where participation_id = ?",
                "sha256:e90-final-" + botId, "sha256:e90-sources-" + botId, utc(CUTOFF_AT), participationId);
        jdbc.update(
                "insert into performance.bot_snapshots "
                        + "(id, bot_id, snapshot_type, source_event_sequence, evaluated_at, equity_amount, "
                        + "total_return_pct, max_drawdown_pct, metrics_document, input_hash, "
                        + "calculation_rules_version, snapshot_hash, created_at) "
                        + "values (?, ?, 'LEADERBOARD_CUTOFF', 2, ?, 110000, 10, 2, '{}'::jsonb, "
                        + "?, 'v1', ?, ?)",
                performanceId, botId, utc(CUTOFF_AT), "sha256:e90-input-" + botId,
                "sha256:e90-performance-" + botId, utc(CUTOFF_AT));
        jdbc.update(
                "update competition.participations set status = 'COMPLETED', evaluation_finished_at = ? "
                        + "where id = ?",
                utc(CUTOFF_AT), participationId);
    }

    private int runCommandCount() {
        return count("select count(*) from operations.outbox_messages where aggregate_id = ? "
                + "and event_type = 'BOT_RUN_COMMAND'", BOT_ID);
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

    private static Clock fixed(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static java.time.OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("e9000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    private static final UUID CREATOR_ID = id(1);
    private static final UUID OWNER_ID = id(2);
    private static final UUID ROOM_ID = id(3);
    private static final UUID TEMPLATE_ID = id(4);
    private static final UUID FEE_ID = id(5);
    private static final UUID BUFFER_ID = id(6);
    private static final UUID STRATEGY_ID = id(7);
    private static final UUID VALIDATION_RUN_ID = id(8);
    private static final UUID CATALOG_ID = id(9);
    private static final UUID PLAN_ID = id(10);
    private static final UUID BOT_ID = id(11);
    private static final UUID INSTRUMENT_ID = id(12);
    private static final UUID FEATURE_ID = id(13);
    private static final UUID PARTICIPATION_ID = id(14);
    private static final UUID EVENT_ID = id(15);
    private static final UUID PERFORMANCE_ID = id(16);
    private static final UUID FINAL_SNAPSHOT_ID = id(17);
    private static final UUID STALE_PARTICIPATION_ID = id(18);
    private static final UUID STALE_EVENT_ID = id(19);
    private static final UUID PEER_OWNER_ID = id(20);
    private static final UUID PEER_STRATEGY_ID = id(21);
    private static final UUID PEER_VALIDATION_RUN_ID = id(22);
    private static final UUID PEER_BOT_ID = id(23);
    private static final UUID PEER_PARTICIPATION_ID = id(24);
    private static final UUID PEER_EVENT_ID = id(25);
    private static final UUID PEER_PERFORMANCE_ID = id(26);

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        CompetitionRoomJpaCommandAdapter.class,
        ScoringTemplateCatalogJooqQueryAdapter.class,
        RoomScheduleTransitionJooqAdapter.class,
        RoomParticipationAdmissionJooqAdapter.class,
        FeatureMaterializationPinResolver.class,
        RoomEvaluationStartJooqAdapter.class,
        PostEvaluationChoiceJooqAdapter.class,
        FinalRoomResultJooqAdapter.class,
        PrivateContinuationTransitionJooqAdapter.class,
        RoomStrategyBotProvisioningJooqAdapter.class,
        BotRunCommandJooqAdapter.class,
    })
    static class TestApplication {}
}

package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.competition.AnonymousLeaderboardQueryService;
import com.idea2strategy.backend.application.competition.CreateUserLiveRoomCommand;
import com.idea2strategy.backend.application.competition.FinalRoomResultCandidate;
import com.idea2strategy.backend.application.competition.FinalRoomResultCommand;
import com.idea2strategy.backend.application.competition.FinalRoomResultConflictException;
import com.idea2strategy.backend.application.competition.FinalRoomResultService;
import com.idea2strategy.backend.application.competition.FinalRoomResultWriteDecision;
import com.idea2strategy.backend.application.competition.LeaderboardAccessException;
import com.idea2strategy.backend.application.competition.OfficialScoringCalculator;
import com.idea2strategy.backend.application.competition.OfficialScoringEligibility;
import com.idea2strategy.backend.application.competition.OfficialScoringIneligibilityReason;
import com.idea2strategy.backend.application.competition.OfficialScoringMetrics;
import com.idea2strategy.backend.application.competition.PostEvaluationAction;
import com.idea2strategy.backend.application.competition.PostEvaluationStopTransitionService;
import com.idea2strategy.backend.application.competition.PrivateContinuationTransitionService;
import com.idea2strategy.backend.application.competition.PublicRoomDiscoveryService;
import com.idea2strategy.backend.application.competition.RoomEvaluationStartService;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionException;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionFailure;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionService;
import com.idea2strategy.backend.application.competition.RoomScheduleTransitionService;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogService;
import com.idea2strategy.backend.application.competition.UserCompetitionRoomCreationService;
import com.idea2strategy.backend.application.competition.UserPostEvaluationChoiceService;
import com.idea2strategy.backend.persistence.backtest.FeatureMaterializationPinResolver;
import com.idea2strategy.backend.domain.competition.RoomAccessType;
import com.idea2strategy.backend.domain.competition.RoomSchedule;
import com.idea2strategy.backend.domain.competition.ScoringComponent;
import com.idea2strategy.backend.domain.competition.ScoringDirection;
import com.idea2strategy.backend.domain.competition.ScoringMetric;
import com.idea2strategy.backend.domain.competition.ScoringTemplateKind;
import com.idea2strategy.backend.domain.competition.ScoringTemplateVersion;
import com.idea2strategy.backend.persistence.botcontrol.BotStopCommandJooqAdapter;
import java.math.BigDecimal;
import java.time.Clock;
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

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = CompetitionJourneyPersistenceE2ETest.TestApplication.class)
class CompetitionJourneyPersistenceE2ETest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-02T00:00:00Z");
    private static final Instant RECRUITMENT_AT = CREATED_AT.plusSeconds(10);
    private static final Instant ADMISSION_AT = CREATED_AT.plusSeconds(30);
    private static final Instant EVALUATION_AT = CREATED_AT.plusSeconds(60);
    private static final Instant CHOICE_AT = CREATED_AT.plusSeconds(90);
    private static final Instant CUTOFF_AT = CREATED_AT.plusSeconds(120);
    private static final Instant FINALIZED_AT = CUTOFF_AT.plusSeconds(10);

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
    @Autowired PublicRoomSearchJooqAdapter publicRoomSearch;
    @Autowired RoomScheduleTransitionJooqAdapter scheduleTransitions;
    @Autowired RoomParticipationAdmissionJooqAdapter admissions;
    @Autowired RoomEvaluationStartJooqAdapter evaluationStarts;
    @Autowired PostEvaluationChoiceJooqAdapter choices;
    @Autowired FinalRoomResultJooqAdapter finalResults;
    @Autowired AnonymousLeaderboardJooqAdapter leaderboards;
    @Autowired PrivateContinuationTransitionJooqAdapter continuations;
    @Autowired PostEvaluationStopTransitionJooqAdapter stops;
    @Autowired JdbcTemplate jdbc;

    @Test
    void runsTheIndependentCompetitionJourneyAcrossPrivacyEligibilityAndBotExitOutcomes() {
        seedReferences();
        createRoom(PUBLIC_ROOM_ID, RoomAccessType.PUBLIC, "Public journey room");
        createRoom(SECRET_ROOM_ID, RoomAccessType.SECRET, "Secret journey room");

        assertThat(new RoomScheduleTransitionService(
                        scheduleTransitions, fixed(RECRUITMENT_AT)).run(10).transitionsApplied())
                .isEqualTo(2);
        assertThat(new PublicRoomDiscoveryService(publicRoomSearch).search("journey", null, 20).items())
                .extracting(item -> item.id())
                .containsExactly(PUBLIC_ROOM_ID);

        grantSecretAdmission(OWNER_ONE_ID, id(90));
        grantSecretAdmission(OWNER_TWO_ID, id(91));
        assertInvalidProvisioningRollsBack();
        admit(OWNER_ONE_ID, PARTICIPATION_ONE_ID, EVENT_ONE_ID, BOT_ONE_ID, "orchid-01");
        admit(OWNER_TWO_ID, PARTICIPATION_TWO_ID, EVENT_TWO_ID, BOT_TWO_ID, "orchid-02");

        new RoomScheduleTransitionService(scheduleTransitions, fixed(EVALUATION_AT)).run(10);
        assertThat(text("select status::text from competition.rooms where id = ?", SECRET_ROOM_ID))
                .isEqualTo("EVALUATING");
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
                EVALUATION_AT.atOffset(ZoneOffset.UTC), SECRET_ROOM_ID);

        assertThatThrownBy(() -> leaderboard(OUTSIDER_ID).query(SECRET_ROOM_ID, null, 20))
                .isInstanceOf(LeaderboardAccessException.class);
        choose(OWNER_ONE_ID, PARTICIPATION_ONE_ID, PostEvaluationAction.CONTINUE_PRIVATE);
        choose(OWNER_TWO_ID, PARTICIPATION_TWO_ID, PostEvaluationAction.STOP_AFTER_EVALUATION);

        assertThat(new RoomScheduleTransitionService(scheduleTransitions, fixed(CUTOFF_AT)).run(10)
                        .transitionsApplied())
                .isPositive();
        fakeTradingResults();

        var command = finalCommand("10", "99");
        var finalization = new FinalRoomResultService(finalResults, fixed(FINALIZED_AT));
        assertThat(finalization.finalize(command)).isEqualTo(FinalRoomResultWriteDecision.CREATED);
        assertThat(finalization.finalize(command))
                .isEqualTo(FinalRoomResultWriteDecision.ALREADY_FINALIZED_IDENTICALLY);

        var ownerTwoPage = leaderboard(OWNER_TWO_ID).query(SECRET_ROOM_ID, null, 20);
        assertThat(ownerTwoPage.items()).extracting(item -> item.anonymousAlias())
                .containsExactly("orchid-01", "orchid-02");
        assertThat(ownerTwoPage.items()).extracting(item -> item.eligibilityStatus())
                .containsExactly("ELIGIBLE", "INELIGIBLE_PRIVATE");
        assertThat(ownerTwoPage.items().getFirst().viewerEvidence()).isNull();
        assertThat(ownerTwoPage.items().getLast().viewerEvidence().botId()).isEqualTo(BOT_TWO_ID);
        assertThatThrownBy(() -> leaderboard(OUTSIDER_ID).query(SECRET_ROOM_ID, null, 20))
                .isInstanceOf(LeaderboardAccessException.class);

        String frozenHash = text(
                "select result_hash from competition.leaderboard_snapshots where room_id = ?", SECRET_ROOM_ID);
        assertThatThrownBy(() -> finalization.finalize(finalCommand("11", "99")))
                .isInstanceOf(FinalRoomResultConflictException.class);
        assertThat(text("select result_hash from competition.leaderboard_snapshots where room_id = ?", SECRET_ROOM_ID))
                .isEqualTo(frozenHash);
        assertThat(count("select count(*) from competition.leaderboard_entries le join "
                        + "competition.leaderboard_snapshots ls on ls.id = le.snapshot_id where ls.room_id = ?",
                SECRET_ROOM_ID)).isEqualTo(2);

        assertThat(new PrivateContinuationTransitionService(continuations, fixed(FINALIZED_AT)).run(10)
                        .transitionsApplied())
                .isEqualTo(1);
        assertThat(new PostEvaluationStopTransitionService(stops, fixed(FINALIZED_AT)).run(10)
                        .transitionsApplied())
                .isEqualTo(1);
        assertThat(new PrivateContinuationTransitionService(continuations, fixed(FINALIZED_AT)).run(10)
                        .transitionsApplied())
                .isZero();
        assertThat(new PostEvaluationStopTransitionService(stops, fixed(FINALIZED_AT)).run(10)
                        .transitionsApplied())
                .isZero();

        assertThat(count("select count(*) from bot.continuation_deadlines where bot_id = ?", BOT_ONE_ID))
                .isOne();
        assertThat(text("select lifecycle_status::text from bot.bots where id = ?", BOT_TWO_ID))
                .isEqualTo("STOPPING");
        assertThat(count("select count(*) from operations.outbox_messages where aggregate_id = ? "
                + "and event_type = 'BOT_STOP_COMMAND'", BOT_TWO_ID)).isOne();
    }

    private void seedReferences() {
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) values "
                        + "(?, 'ACTIVE', ?), (?, 'ACTIVE', ?), (?, 'ACTIVE', ?), (?, 'ACTIVE', ?)",
                CREATOR_ID, utc(CREATED_AT), OWNER_ONE_ID, utc(CREATED_AT),
                OWNER_TWO_ID, utc(CREATED_AT), OUTSIDER_ID, utc(CREATED_AT));
        jdbc.update(
                "insert into competition.scoring_template_versions "
                        + "(id, template_code, version, rules_document, rules_hash, published_at) values "
                        + "(?, 'SINGLE_TOTAL_RETURN_V1', 'e34', ?::jsonb, 'sha256:e34-template', ?)",
                TEMPLATE_ID,
                "{\"kind\":\"SINGLE\",\"calculationRulesVersion\":\"1.0.0\","
                        + "\"components\":[{\"metric\":\"TOTAL_RETURN\","
                        + "\"direction\":\"HIGHER_IS_BETTER\",\"coefficient\":1}],"
                        + "\"adjustments\":[]}",
                utc(CREATED_AT.minusSeconds(1)));
        jdbc.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'E34', '1', 20, 'v1', "
                        + "'sha256:e34-fee', ?, ?)",
                FEE_ID, utc(CREATED_AT.minusSeconds(1)), utc(CREATED_AT.minusSeconds(1)));
        jdbc.update(
                "insert into trading.buying_power_buffer_policy_versions "
                        + "(id, policy_code, version, buffer_bps, rounding_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'E34', '1', 0, 'v1', "
                        + "'sha256:e34-buffer', ?, ?)",
                BUFFER_ID, utc(CREATED_AT.minusSeconds(1)), utc(CREATED_AT.minusSeconds(1)));
        jdbc.update(
                "insert into strategy.element_catalog_versions "
                        + "(id, language_version, schema_version, catalog_version, data_requirement_version, "
                        + "definition_hash, published_at) values (?, 'basic/v1', 'schema/v1', 'catalog/v1', "
                        + "'data/v1', 'e34-catalog', ?)",
                CATALOG_ID, utc(CREATED_AT.minusSeconds(1)));
        jdbc.update(
                "insert into strategy.compiled_flow_plans "
                        + "(id, element_catalog_version_id, semantic_hash, compiler_version, "
                        + "required_feature_set_hash, plan_document, plan_hash, created_at) "
                        + "values (?, ?, 'e34-semantic', 'basic-compiler:1.0.0', 'e34-features', "
                        + "'{}'::jsonb, 'e34-plan', ?)",
                PLAN_ID, CATALOG_ID, utc(CREATED_AT.minusSeconds(1)));
        jdbc.update(
                "insert into market_data.instruments "
                        + "(id, asset_type, primary_exchange_mic, currency_code) values (?, 'STOCK', 'XNAS', 'USD')",
                INSTRUMENT_ID);
    }

    private void createRoom(UUID roomId, RoomAccessType accessType, String name) {
        var catalog = new ScoringTemplateCatalogService(
                scoringCatalogQueries, fixed(CREATED_AT), new ObjectMapper());
        var service = new UserCompetitionRoomCreationService(
                roomCommands, catalog, () -> CREATOR_ID, fixed(CREATED_AT), () -> roomId, new ObjectMapper());
        service.create(new CreateUserLiveRoomCommand(
                name,
                accessType,
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

    private void assertInvalidProvisioningRollsBack() {
        var service = admissionService(OWNER_ONE_ID, INVALID_PARTICIPATION_ID, INVALID_EVENT_ID);
        assertThatThrownBy(() -> service.admit(SECRET_ROOM_ID, "invalid-provision", context -> {
                    insertBot(INVALID_BOT_ID, OWNER_ONE_ID, context.executionEligibleFrom(), true);
                    return INVALID_BOT_ID;
                }))
                .isInstanceOf(RoomParticipationAdmissionException.class)
                .extracting(exception -> ((RoomParticipationAdmissionException) exception).failure())
                .isEqualTo(RoomParticipationAdmissionFailure.PROVISIONED_BOT_INVALID);
        assertThat(count("select count(*) from bot.bots where id = ?", INVALID_BOT_ID)).isZero();
        assertThat(count("select count(*) from competition.participations where id = ?", INVALID_PARTICIPATION_ID))
                .isZero();
    }

    private void admit(UUID ownerId, UUID participationId, UUID eventId, UUID botId, String alias) {
        var admitted = admissionService(ownerId, participationId, eventId)
                .admit(SECRET_ROOM_ID, alias, context -> {
                    insertBot(botId, ownerId, context.executionEligibleFrom(), false);
                    return botId;
                });
        assertThat(admitted.botId()).isEqualTo(botId);
    }

    private void grantSecretAdmission(UUID accountId, UUID invitationId) {
        jdbc.update(
                "insert into competition.room_invitations "
                        + "(id, room_id, issued_by_account_id, credential_type, credential_digest, "
                        + "issued_at, expires_at, claimed_by_account_id, claimed_at) "
                        + "values (?, ?, ?, 'CODE', ?, ?, ?, ?, ?)",
                invitationId,
                SECRET_ROOM_ID,
                CREATOR_ID,
                "journey-" + invitationId,
                utc(CREATED_AT),
                utc(CUTOFF_AT),
                accountId,
                utc(ADMISSION_AT.minusSeconds(1)));
    }

    private RoomParticipationAdmissionService admissionService(
            UUID ownerId, UUID participationId, UUID eventId) {
        return new RoomParticipationAdmissionService(
                admissions, () -> ownerId, fixed(ADMISSION_AT), () -> participationId, () -> eventId);
    }

    private void insertBot(UUID botId, UUID ownerId, Instant executionEligibleFrom, boolean invalid) {
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, "
                        + "execution_eligible_from, created_at, started_at, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', 'E34 bot', 'RUNNING', ?, ?, ?, ?, 0, ?)",
                botId, ownerId, utc(ADMISSION_AT), utc(executionEligibleFrom),
                utc(invalid ? ADMISSION_AT.plusSeconds(1) : ADMISSION_AT), null, utc(ADMISSION_AT));
        if (invalid) {
            return;
        }
        jdbc.update(
                "insert into bot.launch_snapshots "
                        + "(bot_id, snapshot_schema_version, semantic_snapshot, presentation_snapshot, "
                        + "semantic_hash, presentation_hash, snapshot_hash, created_at) values "
                        + "(?, 'basic-launch-snapshot.v1', '{}'::jsonb, '{}'::jsonb, "
                        + "'e34-semantic', 'e34-presentation', ?, ?)",
                botId, "e34-launch-" + botId, utc(ADMISSION_AT));
        jdbc.update(
                "insert into bot.launch_configurations "
                        + "(bot_id, initial_cash_amount, currency_code, broker_rules_version, "
                        + "accounting_rules_version, precision_rules_version, fee_policy_id, "
                        + "slippage_rate_bps, buying_power_buffer_policy_id, candidate_conflict_policy, "
                        + "configuration_hash) values "
                        + "(?, 100000, 'USD', 'v1', 'v1', 'v1', ?, 5, ?, '{}'::jsonb, ?)",
                botId, FEE_ID, BUFFER_ID, "e34-config-" + botId);
        UUID partitionId = UUID.nameUUIDFromBytes((botId + ":partition").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UUID flowId = UUID.nameUUIDFromBytes((botId + ":flow").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update(
                "insert into bot.bot_partitions "
                        + "(id, bot_id, name, budget_cap_bps, position_x, position_y, configuration_hash, created_at, updated_at) "
                        + "values (?, ?, 'Main', 10000, 0, 0, ?, ?, ?)",
                partitionId, botId, "e34-partition-" + botId, utc(ADMISSION_AT), utc(ADMISSION_AT));
        jdbc.update(
                "insert into bot.flows "
                        + "(id, partition_id, name, element_catalog_version_id, compiled_flow_plan_id, position_x, position_y, "
                        + "semantic_document, layout_document, layout_schema_version, semantic_hash, layout_hash, "
                        + "configuration_hash, created_at, updated_at) values "
                        + "(?, ?, 'Journey flow', ?, ?, 0, 0, '{}'::jsonb, '{}'::jsonb, '1', "
                        + "'e34-semantic', 'e34-layout', 'e34-flow', ?, ?)",
                flowId, partitionId, CATALOG_ID, PLAN_ID, utc(ADMISSION_AT), utc(ADMISSION_AT));
        jdbc.update("insert into bot.flow_instruments (flow_id, instrument_id) values (?, ?)", flowId, INSTRUMENT_ID);
    }

    private void choose(UUID ownerId, UUID participationId, PostEvaluationAction action) {
        new UserPostEvaluationChoiceService(choices, () -> ownerId, fixed(CHOICE_AT))
                .update(SECRET_ROOM_ID, participationId, action);
    }

    private void fakeTradingResults() {
        fakeTradingResult(PARTICIPATION_ONE_ID, BOT_ONE_ID, PERFORMANCE_ONE_ID, "10");
        fakeTradingResult(PARTICIPATION_TWO_ID, BOT_TWO_ID, PERFORMANCE_TWO_ID, "99");
    }

    private void fakeTradingResult(
            UUID participationId, UUID botId, UUID performanceSnapshotId, String totalReturn) {
        jdbc.update(
                "update competition.live_evaluation_segments set end_event_sequence = 2, "
                        + "final_state_hash = ?, source_set_hash = ?, "
                        + "virtual_liquidation_document = '{}'::jsonb, finalized_at = ? "
                        + "where participation_id = ?",
                "sha256:final-" + botId, "sha256:sources-" + botId,
                utc(CUTOFF_AT), participationId);
        jdbc.update(
                "insert into performance.bot_snapshots "
                        + "(id, bot_id, snapshot_type, source_event_sequence, evaluated_at, equity_amount, "
                        + "total_return_pct, max_drawdown_pct, sharpe_ratio, metrics_document, input_hash, "
                        + "calculation_rules_version, snapshot_hash, created_at) values "
                        + "(?, ?, 'LEADERBOARD_CUTOFF', 2, ?, 110000, ?, 2, 1, '{}'::jsonb, ?, 'v1', ?, ?)",
                performanceSnapshotId, botId, utc(CUTOFF_AT), new BigDecimal(totalReturn),
                "sha256:input-" + botId, "sha256:performance-" + botId, utc(CUTOFF_AT));
        jdbc.update(
                "update competition.participations set status = 'COMPLETED', evaluation_finished_at = ? "
                        + "where id = ?",
                utc(CUTOFF_AT), participationId);
    }

    private FinalRoomResultCommand finalCommand(String firstReturn, String secondReturn) {
        return new FinalRoomResultCommand(
                SECRET_ROOM_ID,
                TEMPLATE_ID,
                CUTOFF_AT,
                scoringTemplate(),
                List.of(
                        candidate(
                                PARTICIPATION_ONE_ID, PERFORMANCE_ONE_ID, firstReturn,
                                new OfficialScoringEligibility(BigDecimal.ONE, 0, 0, true, List.of())),
                        candidate(
                                PARTICIPATION_TWO_ID, PERFORMANCE_TWO_ID, secondReturn,
                                new OfficialScoringEligibility(
                                        new BigDecimal("0.69"), 0, 0, false,
                                        List.of(OfficialScoringIneligibilityReason.COVERAGE_BELOW_MINIMUM)))));
    }

    private static FinalRoomResultCandidate candidate(
            UUID participationId,
            UUID performanceId,
            String totalReturn,
            OfficialScoringEligibility eligibility) {
        return new FinalRoomResultCandidate(
                participationId,
                performanceId,
                new OfficialScoringMetrics(new BigDecimal(totalReturn), new BigDecimal("2"), BigDecimal.ONE),
                eligibility,
                "sha256:provenance-" + participationId,
                "{\"source\":\"fake-room-performance.v1\"}");
    }

    private static ScoringTemplateVersion scoringTemplate() {
        return new ScoringTemplateVersion(
                TEMPLATE_ID,
                "SINGLE_TOTAL_RETURN_V1",
                "e34",
                ScoringTemplateKind.SINGLE,
                OfficialScoringCalculator.CALCULATION_RULES_VERSION,
                List.of(new ScoringComponent(
                        ScoringMetric.TOTAL_RETURN, ScoringDirection.HIGHER_IS_BETTER, BigDecimal.ONE)),
                List.of(),
                "sha256:e34-template",
                CREATED_AT.minusSeconds(1),
                null);
    }

    private AnonymousLeaderboardQueryService leaderboard(UUID viewerId) {
        return new AnonymousLeaderboardQueryService(leaderboards, () -> viewerId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private static Clock fixed(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static java.time.OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("e3400000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    private static final UUID CREATOR_ID = id(1);
    private static final UUID OWNER_ONE_ID = id(2);
    private static final UUID OWNER_TWO_ID = id(3);
    private static final UUID OUTSIDER_ID = id(4);
    private static final UUID PUBLIC_ROOM_ID = id(5);
    private static final UUID SECRET_ROOM_ID = id(6);
    private static final UUID TEMPLATE_ID = id(7);
    private static final UUID FEE_ID = id(8);
    private static final UUID BUFFER_ID = id(9);
    private static final UUID BOT_ONE_ID = id(10);
    private static final UUID BOT_TWO_ID = id(11);
    private static final UUID PARTICIPATION_ONE_ID = id(12);
    private static final UUID PARTICIPATION_TWO_ID = id(13);
    private static final UUID EVENT_ONE_ID = id(14);
    private static final UUID EVENT_TWO_ID = id(15);
    private static final UUID PERFORMANCE_ONE_ID = id(16);
    private static final UUID PERFORMANCE_TWO_ID = id(17);
    private static final UUID INVALID_BOT_ID = id(18);
    private static final UUID INVALID_PARTICIPATION_ID = id(19);
    private static final UUID INVALID_EVENT_ID = id(20);
    private static final UUID CATALOG_ID = id(21);
    private static final UUID PLAN_ID = id(22);
    private static final UUID INSTRUMENT_ID = id(23);

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        CompetitionRoomJpaCommandAdapter.class,
        ScoringTemplateCatalogJooqQueryAdapter.class,
        PublicRoomSearchJooqAdapter.class,
        RoomScheduleTransitionJooqAdapter.class,
        RoomParticipationAdmissionJooqAdapter.class,
        FeatureMaterializationPinResolver.class,
        RoomEvaluationStartJooqAdapter.class,
        PostEvaluationChoiceJooqAdapter.class,
        FinalRoomResultJooqAdapter.class,
        AnonymousLeaderboardJooqAdapter.class,
        PrivateContinuationTransitionJooqAdapter.class,
        PostEvaluationStopTransitionJooqAdapter.class,
        BotStopCommandJooqAdapter.class
    })
    static class TestApplication {}
}

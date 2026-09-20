package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.competition.FinalRoomResultService;
import com.idea2strategy.backend.application.competition.RoomFinalizationService;
import com.idea2strategy.backend.application.competition.ScoringEvidenceRequest;
import com.idea2strategy.backend.application.competition.ScoringEvidenceService;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogService;
import com.idea2strategy.backend.application.competition.VirtualLiquidationConflictException;
import com.idea2strategy.backend.application.competition.VirtualLiquidationPerformanceCalculator;
import com.idea2strategy.backend.application.competition.VirtualLiquidationQuote;
import com.idea2strategy.backend.application.competition.VirtualLiquidationQuoteHasher;
import com.idea2strategy.backend.application.competition.VirtualLiquidationRequest;
import com.idea2strategy.backend.application.competition.VirtualLiquidationService;
import com.idea2strategy.backend.application.competition.VirtualLiquidationWriteDecision;
import com.idea2strategy.backend.application.performance.EquityObservation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
@SpringBootTest(classes = VirtualLiquidationPersistenceIntegrationTest.TestApplication.class)
class VirtualLiquidationPersistenceIntegrationTest {
    private static final Instant START = Instant.parse("2026-08-02T05:00:00Z");
    private static final Instant CUTOFF = START.plusSeconds(600);
    private static final Instant FINALIZED_AT = CUTOFF.plusSeconds(2);
    private static final String FEE_HASH = "sha256:" + "b".repeat(64);
    private static final String ROOM_RULES_HASH = "sha256:" + "c".repeat(64);

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

    @Autowired VirtualLiquidationJooqAdapter adapter;
    @Autowired ScoringEvidenceJooqAdapter scoringEvidenceAdapter;
    @Autowired RoomFinalizationWorkJooqAdapter finalizationWork;
    @Autowired FinalRoomResultJooqAdapter finalResults;
    @Autowired ScoringTemplateCatalogJooqQueryAdapter scoringTemplates;
    private final ObjectMapper mapper = new ObjectMapper();
    @Autowired JdbcTemplate jdbc;

    private VirtualLiquidationService service;

    @BeforeEach
    void prepare() {
        jdbc.update("delete from competition.leaderboard_entries");
        jdbc.update("delete from competition.leaderboard_snapshots");
        jdbc.update("delete from performance.bot_snapshots");
        jdbc.update("delete from performance.bot_current_projections");
        jdbc.update("delete from competition.live_evaluation_segments");
        jdbc.update("delete from competition.participation_events");
        jdbc.update("delete from competition.participations");
        jdbc.update("delete from competition.live_room_rules");
        jdbc.update("delete from competition.room_schedules");
        jdbc.update("delete from competition.room_rules");
        jdbc.update("delete from competition.rooms");
        jdbc.update("delete from bot.bots");
        jdbc.update("delete from competition.scoring_template_versions where id = ?", TEMPLATE_ID);
        jdbc.update("delete from trading.fee_policy_versions where id = ?", FEE_ID);
        jdbc.update("delete from trading.buying_power_buffer_policy_versions where id = ?", BUFFER_ID);
        jdbc.update("truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
        jdbc.update("delete from identity.accounts where id = ?", OWNER_ID);

        var publishedAt = START.minusSeconds(3600).atOffset(ZoneOffset.UTC);
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", OWNER_ID);
        jdbc.update(
                "insert into competition.scoring_template_versions "
                        + "(id, template_code, version, rules_document, rules_hash, published_at) "
                        + "values (?, 'SINGLE_TOTAL_RETURN_V1', '1', "
                        + "'{\"kind\":\"SINGLE\",\"calculationRulesVersion\":\"official-room-scoring.v1\","
                        + "\"components\":[{\"metric\":\"TOTAL_RETURN\",\"direction\":\"HIGHER_IS_BETTER\","
                        + "\"coefficient\":1.0}],\"adjustments\":[]}'::jsonb, ?, ?)",
                TEMPLATE_ID, "sha256:" + "1".repeat(64), publishedAt);
        jdbc.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'DEFAULT', '1', 20, 'v1', ?, ?, ?)",
                FEE_ID, FEE_HASH, publishedAt, publishedAt);
        jdbc.update(
                "insert into trading.buying_power_buffer_policy_versions "
                        + "(id, policy_code, version, buffer_bps, rounding_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'DEFAULT', '1', 0, 'v1', ?, ?, ?)",
                BUFFER_ID, "sha256:" + "2".repeat(64), publishedAt, publishedAt);
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, creator_account_id, name, access_type, status, created_at) "
                        + "values (?, 'LIVE_PAPER', 'USER', ?, 'Virtual liquidation room', 'PUBLIC', 'EVALUATING', ?)",
                ROOM_ID, OWNER_ID, publishedAt);
        jdbc.update(
                "insert into competition.room_rules "
                        + "(room_id, scoring_template_version_id, initial_cash_amount, bot_participation_limit, "
                        + "per_account_bot_limit, eligibility_document, market_scope_document, scoring_parameters, "
                        + "fee_policy_id, slippage_rate_bps, buying_power_buffer_policy_id, precision_rules_version, "
                        + "rules_hash, locked_at) values (?, ?, 100000, 10, 2, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, "
                        + "?, 5, ?, 'v1', ?, ?)",
                ROOM_ID, TEMPLATE_ID, FEE_ID, BUFFER_ID, ROOM_RULES_HASH, publishedAt);
        jdbc.update(
                "insert into competition.room_schedules "
                        + "(room_id, recruitment_opens_at, participation_opens_at, evaluation_starts_at, "
                        + "participation_closes_at, evaluation_ends_at, finalization_deadline_at, timezone_name) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'UTC')",
                ROOM_ID, utc(START.minusSeconds(120)), utc(START.minusSeconds(120)), utc(START),
                utc(START), utc(CUTOFF), utc(CUTOFF.plusSeconds(300)));
        jdbc.update(
                "insert into competition.live_room_rules "
                        + "(room_id, stopped_bot_slot_policy, minimum_operation_seconds, minimum_fill_count) "
                        + "values (?, 'COUNT_UNTIL_END', 0, 0)",
                ROOM_ID);
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, "
                        + "execution_eligible_from, created_at, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', 'Evaluation bot', 'RUNNING', ?, ?, ?, 0, ?)",
                BOT_ID, OWNER_ID, utc(START), utc(START), publishedAt, utc(START));
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at, evaluation_started_at) "
                        + "values (?, ?, ?, ?, 'anonymous-virtual', 'EVALUATING', ?, ?)",
                PARTICIPATION_ID, ROOM_ID, BOT_ID, OWNER_ID, publishedAt, utc(START));
        jdbc.update(
                "insert into competition.live_evaluation_segments "
                        + "(id, participation_id, segment_type, starts_at, ends_at, start_event_sequence, initial_state_hash) "
                        + "values (?, ?, 'OFFICIAL_EVALUATION', ?, ?, 10, ?)",
                SEGMENT_ID, PARTICIPATION_ID, utc(START), utc(CUTOFF), "sha256:" + "3".repeat(64));
        jdbc.update(
                "insert into performance.bot_current_projections "
                        + "(bot_id, equity_amount, total_return_pct, max_drawdown_pct, metrics_document, "
                        + "ledger_state_hash, position_state_hash, calculation_rules_version, last_event_sequence, "
                        + "projection_hash, updated_at) values (?, 101000, 1, 2, '{}'::jsonb, ?, ?, 'performance-v1', "
                        + "19, ?, ?)",
                BOT_ID, "sha256:" + "4".repeat(64), "sha256:" + "5".repeat(64),
                "sha256:" + "6".repeat(64), utc(CUTOFF.minusSeconds(1)));
        service = new VirtualLiquidationService(
                adapter, context -> quote("15000"), adapter, new VirtualLiquidationPerformanceCalculator(),
                Clock.fixed(FINALIZED_AT, ZoneOffset.UTC));
    }

    @Test
    void atomicallyFreezesSegmentAndSnapshotWithoutMutatingOperationalState() {
        var beforeCurrent = jdbc.queryForMap(
                "select * from performance.bot_current_projections where bot_id = ?", BOT_ID);
        var beforeStatuses = statuses();
        var beforeTradingCounts = tradingCounts();

        var decision = service.finalizeEvaluation(request());

        assertThat(decision).isEqualTo(VirtualLiquidationWriteDecision.CREATED);
        assertThat(jdbc.queryForObject(
                "select count(*) from performance.bot_snapshots where bot_id = ? "
                        + "and snapshot_type = 'LEADERBOARD_CUTOFF'", Integer.class, BOT_ID)).isOne();
        var segment = jdbc.queryForMap(
                "select end_event_sequence, final_state_hash, source_set_hash, "
                        + "virtual_liquidation_document::text as document, finalized_at "
                        + "from competition.live_evaluation_segments where id = ?", SEGMENT_ID);
        assertThat(segment.get("end_event_sequence")).isEqualTo(20L);
        assertThat(segment.get("final_state_hash")).asString().matches("sha256:[0-9a-f]{64}");
        assertThat(segment.get("source_set_hash")).isEqualTo("sha256:" + "f".repeat(64));
        assertThat(segment.get("document").toString())
                .contains("positionCount")
                .doesNotContain("instrument", "quantity", BOT_ID.toString());
        assertThat(statuses()).isEqualTo(beforeStatuses);
        assertThat(tradingCounts()).isEqualTo(beforeTradingCounts);
        assertThat(jdbc.queryForMap(
                "select * from performance.bot_current_projections where bot_id = ?", BOT_ID))
                .isEqualTo(beforeCurrent);
        assertThat(jdbc.queryForObject("select count(*) from competition.leaderboard_snapshots", Integer.class))
                .isZero();

        var evidence = new ScoringEvidenceService(scoringEvidenceAdapter).prepare(
                new ScoringEvidenceRequest(PARTICIPATION_ID, SEGMENT_ID,
                        snapshotId(), TEMPLATE_ID));
        assertThat(evidence.source().evaluationSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(evidence.source().performanceSnapshotId()).isEqualTo(snapshotId());
    }

    @Test
    void identicalRetryIsIdempotentAndChangedPayloadConflicts() {
        assertThat(service.finalizeEvaluation(request()))
                .isEqualTo(VirtualLiquidationWriteDecision.CREATED);

        assertThat(service.finalizeEvaluation(request()))
                .isEqualTo(VirtualLiquidationWriteDecision.ALREADY_FINALIZED_IDENTICALLY);
        assertThat(jdbc.queryForObject("select count(*) from performance.bot_snapshots", Integer.class)).isOne();

        var changedQuoteService = new VirtualLiquidationService(
                adapter, context -> quote("14000"), adapter, new VirtualLiquidationPerformanceCalculator(),
                Clock.fixed(FINALIZED_AT, ZoneOffset.UTC));
        assertThatThrownBy(() -> changedQuoteService.finalizeEvaluation(request()))
                .isInstanceOf(VirtualLiquidationConflictException.class)
                .hasMessageContaining("different evidence");
        assertThat(jdbc.queryForObject("select count(*) from performance.bot_snapshots", Integer.class)).isOne();
    }

    @Test
    void finalizedRetryRemainsAvailableAfterParticipationAndRoomBecomeTerminal() {
        assertThat(service.finalizeEvaluation(request())).isEqualTo(VirtualLiquidationWriteDecision.CREATED);
        jdbc.update(
                "update competition.participations set status = 'COMPLETED'::competition.participation_status, "
                        + "evaluation_finished_at = ? where id = ?",
                utc(FINALIZED_AT), PARTICIPATION_ID);
        jdbc.update(
                "update competition.rooms set status = 'ENDED'::competition.room_status, ended_at = ? where id = ?",
                utc(FINALIZED_AT), ROOM_ID);

        assertThat(service.finalizeEvaluation(request()))
                .isEqualTo(VirtualLiquidationWriteDecision.ALREADY_FINALIZED_IDENTICALLY);
    }

    @Test
    void concurrentFinalizationCreatesOneSnapshotAndReturnsAnIdempotentRetry() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<VirtualLiquidationWriteDecision> finalize = () -> service.finalizeEvaluation(request());
            var results = executor.invokeAll(List.of(finalize, finalize)).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();

            assertThat(results).containsExactlyInAnyOrder(
                    VirtualLiquidationWriteDecision.CREATED,
                    VirtualLiquidationWriteDecision.ALREADY_FINALIZED_IDENTICALLY);
            assertThat(jdbc.queryForObject("select count(*) from performance.bot_snapshots", Integer.class)).isOne();
        }
    }

    @Test
    void corruptedStoredSnapshotNumericEvidenceConflictsOnRetry() {
        assertThat(service.finalizeEvaluation(request())).isEqualTo(VirtualLiquidationWriteDecision.CREATED);
        jdbc.update("update performance.bot_snapshots set equity_amount = equity_amount + 1 where id = ?", snapshotId());

        assertThatThrownBy(() -> service.finalizeEvaluation(request()))
                .isInstanceOf(VirtualLiquidationConflictException.class)
                .hasMessageContaining("different evidence");
    }

    @Test
    void corruptedStoredSnapshotDocumentConflictsOnRetry() {
        assertThat(service.finalizeEvaluation(request())).isEqualTo(VirtualLiquidationWriteDecision.CREATED);
        jdbc.update(
                "update performance.bot_snapshots set metrics_document = '{\"corrupted\":true}'::jsonb where id = ?",
                snapshotId());

        assertThatThrownBy(() -> service.finalizeEvaluation(request()))
                .isInstanceOf(VirtualLiquidationConflictException.class)
                .hasMessageContaining("different evidence");
    }

    @Test
    void scheduledFinalizationCompletesEvidencePublishesOneFinalResultAndConverges() {
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, "
                        + "execution_eligible_from, created_at, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', 'Failed evaluation bot', 'STOPPED', ?, ?, ?, 0, ?)",
                FAILED_BOT_ID, OWNER_ID, utc(CUTOFF), utc(START), utc(START), utc(CUTOFF));
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at, "
                        + "evaluation_started_at, evaluation_finished_at, evaluation_failure_code) "
                        + "values (?, ?, ?, ?, 'anonymous-failed', "
                        + "'EVALUATION_FAILED'::competition.participation_status, ?, ?, ?, 'LEDGER_OPEN_FAILED')",
                FAILED_PARTICIPATION_ID, ROOM_ID, FAILED_BOT_ID, OWNER_ID,
                utc(START.minusSeconds(120)), utc(START), utc(CUTOFF));
        jdbc.update(
                "update competition.rooms set status = 'ENDED'::competition.room_status, ended_at = ? where id = ?",
                utc(CUTOFF), ROOM_ID);
        Clock clock = Clock.fixed(FINALIZED_AT, ZoneOffset.UTC);
        var finalization = new RoomFinalizationService(
                finalizationWork,
                service,
                new ScoringEvidenceService(scoringEvidenceAdapter),
                new FinalRoomResultService(finalResults, clock),
                new ScoringTemplateCatalogService(scoringTemplates, clock, mapper),
                clock,
                mapper);

        var first = finalization.run(10);

        assertThat(first.roomsAttempted()).isOne();
        assertThat(first.roomsFinalized()).isOne();
        assertThat(first.participationsFinalized()).isOne();
        assertThat(first.failures()).isEmpty();
        assertThat(jdbc.queryForObject(
                "select status::text from competition.participations where id = ?",
                String.class, PARTICIPATION_ID)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "select count(*) from competition.leaderboard_snapshots "
                        + "where room_id = ? and status = 'FINAL'",
                Integer.class, ROOM_ID)).isOne();
        assertThat(jdbc.queryForObject(
                "select count(*) from competition.leaderboard_entries le "
                        + "join competition.leaderboard_snapshots ls on ls.id = le.snapshot_id "
                        + "where ls.room_id = ? and le.performance_snapshot_id = ? "
                        + "and le.backtest_aggregate_result_id is null",
                Integer.class, ROOM_ID, snapshotId())).isOne();

        var retry = finalization.run(10);
        assertThat(retry.roomsAttempted()).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from competition.leaderboard_snapshots where room_id = ?",
                Integer.class, ROOM_ID)).isOne();
        assertThat(jdbc.queryForObject(
                "select count(*) from performance.bot_snapshots where bot_id = ?",
                Integer.class, BOT_ID)).isOne();
    }

    private VirtualLiquidationRequest request() {
        return new VirtualLiquidationRequest(PARTICIPATION_ID, SEGMENT_ID);
    }

    private VirtualLiquidationQuote quote(String grossProceeds) {
        var proceeds = new BigDecimal(grossProceeds);
        var delta = proceeds.subtract(new BigDecimal("500")).subtract(new BigDecimal("9.50"));
        var unsigned = new VirtualLiquidationQuote(
                ROOM_ID, PARTICIPATION_ID, BOT_ID, SEGMENT_ID, CUTOFF, 20,
                new BigDecimal("95000"), delta, List.of(
                        new EquityObservation(10, new BigDecimal("100000")),
                        new EquityObservation(19, new BigDecimal("95000"))),
                new BigDecimal("1.25"), 2, proceeds, new BigDecimal("500"),
                new BigDecimal("9.50"), FEE_ID, FEE_HASH, 5,
                "sha256:" + "d".repeat(64), "sha256:" + "e".repeat(64),
                "sha256:" + "f".repeat(64), VirtualLiquidationQuote.CONTRACT_VERSION,
                "f-virtual-liquidation.v1", "sha256:" + "0".repeat(64));
        return unsigned.withQuoteHash(VirtualLiquidationQuoteHasher.hash(unsigned));
    }

    private List<Object> statuses() {
        return List.of(
                jdbc.queryForObject("select status::text from competition.rooms where id = ?", String.class, ROOM_ID),
                jdbc.queryForObject("select status::text from competition.participations where id = ?", String.class,
                        PARTICIPATION_ID),
                jdbc.queryForObject("select lifecycle_status::text from bot.bots where id = ?", String.class, BOT_ID));
    }

    private List<Integer> tradingCounts() {
        return List.of(
                jdbc.queryForObject("select count(*) from trading.orders", Integer.class),
                jdbc.queryForObject("select count(*) from trading.fills", Integer.class),
                jdbc.queryForObject("select count(*) from trading.ledger_transactions", Integer.class),
                jdbc.queryForObject("select count(*) from trading.ledger_entries", Integer.class),
                jdbc.queryForObject("select count(*) from trading.position_lots", Integer.class),
                jdbc.queryForObject("select count(*) from trading.system_close_actions", Integer.class));
    }

    private static UUID snapshotId() {
        return UUID.nameUUIDFromBytes(
                ("virtual-liquidation-snapshot.v1:" + SEGMENT_ID)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static java.time.OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("b9000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    private static final UUID OWNER_ID = id(1);
    private static final UUID ROOM_ID = id(2);
    private static final UUID BOT_ID = id(3);
    private static final UUID PARTICIPATION_ID = id(4);
    private static final UUID TEMPLATE_ID = id(5);
    private static final UUID FEE_ID = id(6);
    private static final UUID BUFFER_ID = id(7);
    private static final UUID SEGMENT_ID = id(8);
    private static final UUID FAILED_BOT_ID = id(9);
    private static final UUID FAILED_PARTICIPATION_ID = id(10);

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            VirtualLiquidationJooqAdapter.class,
            ScoringEvidenceJooqAdapter.class,
            RoomFinalizationWorkJooqAdapter.class,
            FinalRoomResultJooqAdapter.class,
            ScoringTemplateCatalogJooqQueryAdapter.class
    })
    static class TestApplication {}
}

package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.competition.AnonymousLeaderboardQueryService;
import com.idea2strategy.backend.application.competition.BacktestCompetitionSettlementService;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@org.springframework.boot.test.context.SpringBootTest(
        classes = BacktestCompetitionSettlementPersistenceIntegrationTest.TestApplication.class)
class BacktestCompetitionSettlementPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-10T03:00:00Z");

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

    @Autowired BacktestCompetitionSettlementJooqAdapter settlement;
    @Autowired AnonymousLeaderboardJooqAdapter leaderboards;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aggregatesEverySuccessfulPeriodFailsOnlyTheBrokenParticipationAndPublishesFinalBacktestRanks() {
        seedJourney();
        var service = new BacktestCompetitionSettlementService(
                settlement, Clock.fixed(NOW, ZoneOffset.UTC));

        var first = service.run(100);

        assertThat(first.participantsCompleted()).isOne();
        assertThat(first.participantsFailed()).isEqualTo(2);
        assertThat(first.publishedSnapshots()).isOne();
        assertThat(first.finalSnapshots()).isZero();
        assertThat(text("select status::text from competition.participations where id = ?", GOOD_PARTICIPATION))
                .isEqualTo("COMPLETED");
        assertThat(text("select status::text from competition.participations where id = ?", BAD_PARTICIPATION))
                .isEqualTo("EVALUATION_FAILED");
        assertThat(text("select evaluation_failure_code from competition.participations where id = ?", BAD_PARTICIPATION))
                .isEqualTo("PERMANENT_INPUT_FAILURE");
        assertThat(text("select evaluation_failure_code from competition.participations where id = ?", INVALID_PARTICIPATION))
                .isEqualTo("BACKTEST_RESULT_EVIDENCE_INVALID");
        assertThat(count("select count(*) from competition.backtest_period_runs "
                + "where participation_id = ? and verified_at is not null", INVALID_PARTICIPATION)).isZero();
        assertThat(count("select count(*) from competition.backtest_aggregate_results")).isOne();
        assertThat(decimal("select weighted_return_pct from competition.backtest_aggregate_results"))
                .isEqualByComparingTo("15.00000000");
        assertThat(decimal("select weighted_max_drawdown_pct from competition.backtest_aggregate_results"))
                .isEqualByComparingTo("4.00000000");
        assertThat(decimal("select weighted_sharpe_ratio from competition.backtest_aggregate_results"))
                .isEqualByComparingTo("1.50000000");
        assertThat(count("select count(*) from competition.backtest_period_runs "
                + "where participation_id = ? and verified_at is not null and locked_result_hash is not null",
                GOOD_PARTICIPATION)).isEqualTo(2);

        UUID aggregateId = jdbc.queryForObject(
                "select id from competition.backtest_aggregate_results where participation_id = ?",
                UUID.class, GOOD_PARTICIPATION);
        UUID publishedSnapshot = jdbc.queryForObject(
                "select id from competition.leaderboard_snapshots where room_id = ? and status = 'PUBLISHED'",
                UUID.class, ROOM);
        assertThat(jdbc.queryForObject(
                "select backtest_aggregate_result_id from competition.leaderboard_entries "
                        + "where snapshot_id = ? and participation_id = ?",
                UUID.class, publishedSnapshot, GOOD_PARTICIPATION)).isEqualTo(aggregateId);
        assertThat(count("select count(*) from competition.leaderboard_entries "
                + "where snapshot_id = ? and performance_snapshot_id is not null", publishedSnapshot)).isZero();

        assertThatThrownBy(() -> jdbc.update(
                        "update competition.leaderboard_entries set backtest_aggregate_result_id = null, "
                                + "performance_snapshot_id = ? where snapshot_id = ? and participation_id = ?",
                        LIVE_PERFORMANCE, publishedSnapshot, GOOD_PARTICIPATION))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("BACKTEST leaderboard requires a backtest aggregate result");

        var published = new AnonymousLeaderboardQueryService(leaderboards, () -> OUTSIDER)
                .query(ROOM, null, 20);
        assertThat(published.snapshotStatus()).isEqualTo("PUBLISHED");
        assertThat(published.items()).singleElement().satisfies(item -> {
            assertThat(item.rank()).isOne();
            assertThat(item.anonymousAlias()).isEqualTo("int04-a-good");
            assertThat(item.totalReturnPct()).isEqualByComparingTo("15.00000000");
            assertThat(item.viewerEvidence()).isNull();
        });

        jdbc.update(
                "update competition.rooms set status = 'ENDED'::competition.room_status, ended_at = ? "
                        + "where id = ?",
                utc(NOW), ROOM);
        var finalized = service.run(100);
        assertThat(finalized.finalSnapshots()).isOne();
        assertThat(count("select count(*) from competition.leaderboard_snapshots "
                + "where room_id = ? and status = 'FINAL'", ROOM)).isOne();
        var finalPage = new AnonymousLeaderboardQueryService(leaderboards, () -> OUTSIDER)
                .query(ROOM, null, 20);
        assertThat(finalPage.snapshotStatus()).isEqualTo("FINAL");
        assertThat(finalPage.items()).singleElement();

        var duplicate = service.run(100);
        assertThat(duplicate.participantsCompleted()).isZero();
        assertThat(duplicate.participantsFailed()).isZero();
        assertThat(duplicate.publishedSnapshots()).isZero();
        assertThat(duplicate.finalSnapshots()).isZero();
        assertThat(count("select count(*) from competition.backtest_aggregate_results")).isOne();
        assertThat(count("select count(*) from competition.leaderboard_snapshots where room_id = ?", ROOM))
                .isEqualTo(2);
    }

    private void seedJourney() {
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) values "
                        + "(?, 'ACTIVE', ?), (?, 'ACTIVE', ?), (?, 'ACTIVE', ?), (?, 'ACTIVE', ?)",
                GOOD_OWNER, utc(NOW.minusSeconds(100)), BAD_OWNER, utc(NOW.minusSeconds(100)),
                INVALID_OWNER, utc(NOW.minusSeconds(100)), OUTSIDER, utc(NOW.minusSeconds(100)));
        jdbc.update(
                "insert into operations.operator_accounts "
                        + "(id, external_identity_key_hmac, external_identity_key_version, status, "
                        + "mfa_enrolled_at, created_at) values (?, 'int04-a-operator', 1, 'ACTIVE', ?, ?)",
                OPERATOR, utc(NOW.minusSeconds(100)), utc(NOW.minusSeconds(100)));
        jdbc.update(
                "insert into competition.scoring_template_versions "
                        + "(id, template_code, version, rules_document, rules_hash, published_at) values "
                        + "(?, 'SINGLE_TOTAL_RETURN_V1', 'int04-a', ?::jsonb, ?, ?)",
                TEMPLATE,
                "{\"kind\":\"SINGLE\",\"calculationRulesVersion\":\"official-room-scoring.v1\","
                        + "\"components\":[{\"metric\":\"TOTAL_RETURN\","
                        + "\"direction\":\"HIGHER_IS_BETTER\",\"coefficient\":1}],\"adjustments\":[]}",
                hash('a'), utc(NOW.minusSeconds(100)));
        jdbc.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'INT04_A', '1', 20, 'v1', ?, ?, ?)",
                FEE, hash('b'), utc(NOW.minusSeconds(100)), utc(NOW.minusSeconds(100)));
        jdbc.update(
                "insert into trading.buying_power_buffer_policy_versions "
                        + "(id, policy_code, version, buffer_bps, rounding_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'INT04_A', '1', 0, 'v1', ?, ?, ?)",
                BUFFER, hash('c'), utc(NOW.minusSeconds(100)), utc(NOW.minusSeconds(100)));
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, created_by_operator_id, name, access_type, "
                        + "status, created_at) values "
                        + "(?, 'BACKTEST', 'PLATFORM', ?, 'INT04-A official', 'PUBLIC', 'EVALUATING', ?)",
                ROOM, OPERATOR, utc(NOW.minusSeconds(100)));
        jdbc.update(
                "insert into competition.room_rules "
                        + "(room_id, scoring_template_version_id, initial_cash_amount, bot_participation_limit, "
                        + "per_account_bot_limit, eligibility_document, market_scope_document, scoring_parameters, "
                        + "fee_policy_id, slippage_rate_bps, buying_power_buffer_policy_id, precision_rules_version, "
                        + "rules_hash, locked_at) values (?, ?, 100000, 10, 2, '{}'::jsonb, '{}'::jsonb, "
                        + "'{}'::jsonb, ?, 5, ?, 'v1', ?, ?)",
                ROOM, TEMPLATE, FEE, BUFFER, hash('d'), utc(NOW.minusSeconds(100)));
        jdbc.update(
                "insert into competition.room_schedules "
                        + "(room_id, recruitment_opens_at, participation_opens_at, evaluation_starts_at, "
                        + "participation_closes_at, evaluation_ends_at, finalization_deadline_at, timezone_name) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'UTC')",
                ROOM, utc(NOW.minusSeconds(100)), utc(NOW.minusSeconds(90)), utc(NOW.minusSeconds(80)),
                utc(NOW.plusSeconds(60)), utc(NOW.plusSeconds(120)), utc(NOW.plusSeconds(180)));
        jdbc.update(
                "insert into competition.backtest_evaluation_plans "
                        + "(room_id, plan_version, period_count, plan_hash, commitment_hash, "
                        + "commitment_nonce_ciphertext, nonce_key_version, locked_at) "
                        + "values (?, 'int04-a.v1', 2, ?, ?, 'ciphertext', 1, ?)",
                ROOM, hash('e'), hash('f'), utc(NOW.minusSeconds(100)));
        jdbc.update(
                "insert into backtest.execution_policy_versions "
                        + "(version, policy_artifact_hash, policy_document, locked_at) "
                        + "values ('competition-v1', ?, jsonb_build_object('competitionPlanHash', ?), ?)",
                hash('0'), hash('e'), utc(NOW.minusSeconds(100)));
        jdbc.update(
                "insert into competition.backtest_evaluation_periods "
                        + "(id, evaluation_plan_room_id, period_sequence, evaluation_start, evaluation_end, "
                        + "importance_weight, input_set_hash) values "
                        + "(?, ?, 1, '2024-01-01', '2024-06-30', 0.5, ?), "
                        + "(?, ?, 2, '2024-07-01', '2024-12-31', 0.5, ?)",
                PERIOD_ONE, ROOM, hash('1'), PERIOD_TWO, ROOM, hash('2'));
        seedBotAndParticipation(GOOD_BOT, GOOD_OWNER, GOOD_PARTICIPATION, "int04-a-good");
        seedBotAndParticipation(BAD_BOT, BAD_OWNER, BAD_PARTICIPATION, "int04-a-bad");
        seedRun(GOOD_RUN_ONE, GOOD_BOT, GOOD_OWNER, GOOD_PARTICIPATION, PERIOD_ONE,
                "COMPLETED", null, hash('3'), "10", "-2", "1");
        seedRun(GOOD_RUN_TWO, GOOD_BOT, GOOD_OWNER, GOOD_PARTICIPATION, PERIOD_TWO,
                "COMPLETED", null, hash('4'), "20", "-6", "2");
        seedRun(BAD_RUN_ONE, BAD_BOT, BAD_OWNER, BAD_PARTICIPATION, PERIOD_ONE,
                "COMPLETED", null, hash('5'), "30", "-3", "3");
        seedRun(BAD_RUN_TWO, BAD_BOT, BAD_OWNER, BAD_PARTICIPATION, PERIOD_TWO,
                "FAILED", "PERMANENT_INPUT_FAILURE", null, null, null, null);
        seedBotAndParticipation(INVALID_BOT, INVALID_OWNER, INVALID_PARTICIPATION, "int04-a-invalid");
        seedRun(INVALID_RUN_ONE, INVALID_BOT, INVALID_OWNER, INVALID_PARTICIPATION, PERIOD_ONE,
                "COMPLETED", null, hash('c'), "7", "-1", "1");
        seedRun(INVALID_RUN_TWO, INVALID_BOT, INVALID_OWNER, INVALID_PARTICIPATION, PERIOD_TWO,
                "COMPLETED", null, hash('d'), "8", "-2", "1");
        jdbc.update(
                "update backtest.performance_summaries set metrics_document = "
                        + "metrics_document - 'totalReturnPct' where run_id = ?",
                INVALID_RUN_TWO);
        jdbc.update(
                "insert into performance.bot_snapshots "
                        + "(id, bot_id, snapshot_type, source_event_sequence, evaluated_at, equity_amount, "
                        + "total_return_pct, max_drawdown_pct, sharpe_ratio, metrics_document, input_hash, "
                        + "calculation_rules_version, snapshot_hash, created_at) values "
                        + "(?, ?, 'LEADERBOARD_CUTOFF', 1, ?, 100000, 99, 1, 1, '{}'::jsonb, ?, 'v1', ?, ?)",
                LIVE_PERFORMANCE, GOOD_BOT, utc(NOW.minusSeconds(1)), hash('6'), hash('7'),
                utc(NOW.minusSeconds(1)));
    }

    private void seedBotAndParticipation(
            UUID botId, UUID ownerId, UUID participationId, String alias) {
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, "
                        + "execution_eligible_from, created_at, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', ?, 'RUNNING', ?, ?, ?, 0, ?)",
                botId, ownerId, alias, utc(NOW.minusSeconds(100)), utc(NOW.minusSeconds(100)),
                utc(NOW.minusSeconds(100)), utc(NOW.minusSeconds(100)));
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at, "
                        + "evaluation_started_at) values (?, ?, ?, ?, ?, 'EVALUATING', ?, ?)",
                participationId, ROOM, botId, ownerId, alias,
                utc(NOW.minusSeconds(90)), utc(NOW.minusSeconds(80)));
    }

    private void seedRun(
            UUID runId,
            UUID botId,
            UUID ownerId,
            UUID participationId,
            UUID periodId,
            String status,
            String failureCode,
            String resultHash,
            String totalReturn,
            String maxDrawdown,
            String sharpe) {
        UUID messageId = UUID.nameUUIDFromBytes(("message:" + runId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update(
                "insert into backtest.runs "
                        + "(id, lane, message_id, bot_id, owner_account_id, configuration_hash, "
                        + "canonical_payload_hash, aggregate_sequence, status, evaluation_start, evaluation_end, "
                        + "initial_cash_amount, market_rules_version, accounting_rules_version, "
                        + "execution_policy_version, precision_rules_version, fee_policy_id, slippage_rate_bps, "
                        + "buying_power_buffer_policy_id, idempotency_scope, idempotency_key, queued_at, "
                        + "completed_at, failure_code, result_hash) values "
                        + "(?, 'COMPETITION', ?, ?, ?, ?, ?, 1, ?::backtest.run_status, '2024-01-01', "
                        + "'2024-12-31', 100000, 'v1', 'v1', 'competition-v1', 'v1', ?, 5, ?, ?, ?, ?, ?, ?, ?)",
                runId, messageId, botId, ownerId, hash('8'), hash('9'), status, FEE, BUFFER,
                participationId.toString(), "int04-a:" + runId, utc(NOW.minusSeconds(70)),
                utc(NOW.minusSeconds(10)), failureCode, resultHash);
        jdbc.update(
                "insert into competition.backtest_period_runs "
                        + "(participation_id, evaluation_period_id, run_id) values (?, ?, ?)",
                participationId, periodId, runId);
        if ("COMPLETED".equals(status)) {
            jdbc.update(
                    "insert into backtest.performance_summaries "
                            + "(run_id, metric_catalog_version, metrics_document, calculation_rules_version, "
                            + "source_set_hash, input_hash, result_hash, calculated_at) values "
                            + "(?, 'backtest-metrics.v1', jsonb_build_object('totalReturnPct', ?::numeric, "
                            + "'maxDrawdownPct', ?::numeric, 'sharpe', ?::numeric), 'backtest.v1', ?, ?, ?, ?)",
                    runId, new BigDecimal(totalReturn), new BigDecimal(maxDrawdown), new BigDecimal(sharpe),
                    hash('a'), hash('b'), resultHash, utc(NOW.minusSeconds(10)));
        }
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private BigDecimal decimal(String sql, Object... args) {
        return jdbc.queryForObject(sql, BigDecimal.class, args);
    }

    private static java.time.OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static String hash(char digit) {
        return "sha256:" + Character.toString(digit).repeat(64);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a0400000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    private static final UUID GOOD_OWNER = id(1);
    private static final UUID BAD_OWNER = id(2);
    private static final UUID OUTSIDER = id(3);
    private static final UUID OPERATOR = id(4);
    private static final UUID TEMPLATE = id(5);
    private static final UUID FEE = id(6);
    private static final UUID BUFFER = id(7);
    private static final UUID ROOM = id(8);
    private static final UUID PERIOD_ONE = id(9);
    private static final UUID PERIOD_TWO = id(10);
    private static final UUID GOOD_BOT = id(11);
    private static final UUID BAD_BOT = id(12);
    private static final UUID GOOD_PARTICIPATION = id(13);
    private static final UUID BAD_PARTICIPATION = id(14);
    private static final UUID GOOD_RUN_ONE = id(15);
    private static final UUID GOOD_RUN_TWO = id(16);
    private static final UUID BAD_RUN_ONE = id(17);
    private static final UUID BAD_RUN_TWO = id(18);
    private static final UUID LIVE_PERFORMANCE = id(19);
    private static final UUID INVALID_OWNER = id(20);
    private static final UUID INVALID_BOT = id(21);
    private static final UUID INVALID_PARTICIPATION = id(22);
    private static final UUID INVALID_RUN_ONE = id(23);
    private static final UUID INVALID_RUN_TWO = id(24);

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        ScoringTemplateCatalogJooqQueryAdapter.class,
        BacktestCompetitionSettlementJooqAdapter.class,
        AnonymousLeaderboardJooqAdapter.class
    })
    static class TestApplication {
        @Bean
        ScoringTemplateCatalogService scoringTemplateCatalogService(
                ScoringTemplateCatalogJooqQueryAdapter adapter) {
            return new ScoringTemplateCatalogService(
                    adapter, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
        }
    }
}

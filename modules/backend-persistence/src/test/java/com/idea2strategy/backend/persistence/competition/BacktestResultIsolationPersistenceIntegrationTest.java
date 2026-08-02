package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.competition.AnonymousLeaderboardQueryService;
import com.idea2strategy.backend.application.competition.OwnedBotComparisonQueryService;
import com.idea2strategy.backend.messaging.backtest.v1.BacktestResultContractFixtures;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = BacktestResultIsolationPersistenceIntegrationTest.TestApplication.class)
class BacktestResultIsolationPersistenceIntegrationTest {
    private static final Instant AT = Instant.parse("2026-08-02T15:00:00Z");

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

    @Autowired AnonymousLeaderboardJooqAdapter leaderboards;
    @Autowired JdbcTemplate jdbc;

    @Test
    void keepsD93BacktestEvidenceOwnerOnlyAndOutOfTheLiveOfficialSnapshot() {
        seedRoomsAndResults();
        var provider = BacktestResultContractFixtures.completed();

        assertThat(provider.source()).isEqualTo("BACKTEST");
        assertThat(provider.eventType()).isEqualTo("BACKTEST_RESULT");
        assertThat(provider.livePerformanceEligible()).isFalse();

        var ownerPage = new OwnedBotComparisonQueryService(leaderboards, () -> OWNER_ID)
                .query(BACKTEST_ROOM_ID, null, 20);
        assertThat(ownerPage.items()).singleElement().satisfies(item -> {
            assertThat(item.anonymousAlias()).isEqualTo("backtest-orchid");
            assertThat(item.evidence().botId()).isEqualTo(BACKTEST_BOT_ID);
            assertThat(item.evidence().backtestAggregateResultId()).isEqualTo(BACKTEST_AGGREGATE_ID);
        });
        assertThat(new OwnedBotComparisonQueryService(leaderboards, () -> OUTSIDER_ID)
                        .query(BACKTEST_ROOM_ID, null, 20).items())
                .isEmpty();
        assertThat(new AnonymousLeaderboardQueryService(leaderboards, () -> OUTSIDER_ID)
                        .query(BACKTEST_ROOM_ID, null, 20).items())
                .singleElement()
                .satisfies(item -> assertThat(item.viewerEvidence()).isNull());

        String resultHash = text(
                "select result_hash from competition.leaderboard_snapshots where id = ?", LIVE_SNAPSHOT_ID);
        for (int deliveryAttempt = 1; deliveryAttempt <= 2; deliveryAttempt++) {
            assertThatThrownBy(() -> jdbc.update(
                            "update competition.leaderboard_entries set performance_snapshot_id = null, "
                                    + "backtest_aggregate_result_id = ? "
                                    + "where snapshot_id = ? and participation_id = ?",
                            BACKTEST_AGGREGATE_ID, LIVE_SNAPSHOT_ID, LIVE_PARTICIPATION_ID))
                    .as("D93 result delivery attempt %s", deliveryAttempt)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("LIVE_PAPER leaderboard requires a live performance snapshot");
        }

        assertThat(text("select result_hash from competition.leaderboard_snapshots where id = ?", LIVE_SNAPSHOT_ID))
                .isEqualTo(resultHash);
        assertThat(jdbc.queryForObject(
                        "select performance_snapshot_id from competition.leaderboard_entries "
                                + "where snapshot_id = ? and participation_id = ?",
                        UUID.class, LIVE_SNAPSHOT_ID, LIVE_PARTICIPATION_ID))
                .isEqualTo(LIVE_PERFORMANCE_ID);
        assertThat(count("select count(*) from performance.bot_snapshots where bot_id = ?", LIVE_BOT_ID))
                .isOne();
    }

    private void seedRoomsAndResults() {
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status, status_changed_at) values "
                        + "(?, 'ACTIVE', ?), (?, 'ACTIVE', ?)",
                OWNER_ID, utc(AT.minusSeconds(10)), OUTSIDER_ID, utc(AT.minusSeconds(10)));
        jdbc.update(
                "insert into operations.operator_accounts "
                        + "(id, external_identity_key_hmac, status, mfa_enrolled_at, created_at) "
                        + "values (?, 'e92-operator', 'ACTIVE', ?, ?)",
                OPERATOR_ID, utc(AT.minusSeconds(10)), utc(AT.minusSeconds(10)));
        jdbc.update(
                "insert into competition.scoring_template_versions "
                        + "(id, template_code, version, rules_document, rules_hash, published_at) "
                        + "values (?, 'SINGLE_TOTAL_RETURN_V1', 'e92', '{}'::jsonb, 'sha256:e92-template', ?)",
                TEMPLATE_ID, utc(AT.minusSeconds(10)));
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, created_by_operator_id, name, access_type, "
                        + "status, created_at, ended_at) values "
                        + "(?, 'LIVE_PAPER', 'PLATFORM', ?, 'E92 live', 'PUBLIC', 'ENDED', ?, ?), "
                        + "(?, 'BACKTEST', 'PLATFORM', ?, 'E92 backtest', 'PUBLIC', 'ENDED', ?, ?)",
                LIVE_ROOM_ID, OPERATOR_ID, utc(AT.minusSeconds(10)), utc(AT),
                BACKTEST_ROOM_ID, OPERATOR_ID, utc(AT.minusSeconds(10)), utc(AT));
        insertBot(LIVE_BOT_ID, "E92 live bot");
        insertBot(BACKTEST_BOT_ID, "E92 backtest bot");
        insertParticipation(
                LIVE_PARTICIPATION_ID, LIVE_ROOM_ID, LIVE_BOT_ID, "live-orchid");
        insertParticipation(
                BACKTEST_PARTICIPATION_ID, BACKTEST_ROOM_ID, BACKTEST_BOT_ID, "backtest-orchid");
        jdbc.update(
                "insert into competition.backtest_evaluation_plans "
                        + "(room_id, plan_version, period_count, plan_hash, commitment_hash, "
                        + "commitment_nonce_ciphertext, nonce_key_version, locked_at, disclosed_at) "
                        + "values (?, 'e92', 2, 'e92-plan', 'e92-commitment', 'ciphertext', 1, ?, ?)",
                BACKTEST_ROOM_ID, utc(AT.minusSeconds(10)), utc(AT));
        jdbc.update(
                "insert into competition.backtest_aggregate_results "
                        + "(id, participation_id, evaluation_plan_room_id, scoring_template_version_id, "
                        + "weighted_return_pct, weighted_sharpe_ratio, weighted_max_drawdown_pct, "
                        + "worst_period_max_drawdown_pct, final_score, metrics_document, period_result_set_hash, "
                        + "calculation_rules_version, aggregate_hash, calculated_at, verified_at, published_at) "
                        + "values (?, ?, ?, ?, 12, 1, 2, 3, 12, '{}'::jsonb, 'e92-periods', 'v1', "
                        + "'e92-aggregate', ?, ?, ?)",
                BACKTEST_AGGREGATE_ID, BACKTEST_PARTICIPATION_ID, BACKTEST_ROOM_ID, TEMPLATE_ID,
                utc(AT.minusSeconds(3)), utc(AT.minusSeconds(2)), utc(AT.minusSeconds(1)));
        jdbc.update(
                "insert into performance.bot_snapshots "
                        + "(id, bot_id, snapshot_type, source_event_sequence, evaluated_at, equity_amount, "
                        + "total_return_pct, max_drawdown_pct, metrics_document, input_hash, "
                        + "calculation_rules_version, snapshot_hash, created_at) "
                        + "values (?, ?, 'LEADERBOARD_CUTOFF', 1, ?, 110000, 10, 2, '{}'::jsonb, "
                        + "'sha256:e92-input', 'v1', 'sha256:e92-live', ?)",
                LIVE_PERFORMANCE_ID, LIVE_BOT_ID, utc(AT), utc(AT));
        insertSnapshot(LIVE_SNAPSHOT_ID, LIVE_ROOM_ID, "sha256:e92-live-result");
        insertSnapshot(BACKTEST_SNAPSHOT_ID, BACKTEST_ROOM_ID, "sha256:e92-backtest-result");
        jdbc.update(
                "insert into competition.leaderboard_entries "
                        + "(snapshot_id, participation_id, performance_snapshot_id, rank, is_joint_rank, "
                        + "eligibility_status, score, tie_break_document, calculation_document) "
                        + "values (?, ?, ?, 1, false, 'ELIGIBLE', 10, '{}'::jsonb, '{}'::jsonb)",
                LIVE_SNAPSHOT_ID, LIVE_PARTICIPATION_ID, LIVE_PERFORMANCE_ID);
        jdbc.update(
                "insert into competition.leaderboard_entries "
                        + "(snapshot_id, participation_id, backtest_aggregate_result_id, rank, is_joint_rank, "
                        + "eligibility_status, score, tie_break_document, calculation_document) "
                        + "values (?, ?, ?, 1, false, 'ELIGIBLE', 12, '{}'::jsonb, '{}'::jsonb)",
                BACKTEST_SNAPSHOT_ID, BACKTEST_PARTICIPATION_ID, BACKTEST_AGGREGATE_ID);
    }

    private void insertBot(UUID botId, String name) {
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, "
                        + "execution_eligible_from, created_at, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', ?, 'RUNNING', ?, ?, ?, 0, ?)",
                botId, OWNER_ID, name, utc(AT.minusSeconds(10)), utc(AT.minusSeconds(10)),
                utc(AT.minusSeconds(10)), utc(AT.minusSeconds(10)));
    }

    private void insertParticipation(UUID participationId, UUID roomId, UUID botId, String alias) {
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at, "
                        + "evaluation_started_at, evaluation_finished_at) "
                        + "values (?, ?, ?, ?, ?, 'COMPLETED', ?, ?, ?)",
                participationId, roomId, botId, OWNER_ID, alias,
                utc(AT.minusSeconds(10)), utc(AT.minusSeconds(5)), utc(AT));
    }

    private void insertSnapshot(UUID snapshotId, UUID roomId, String resultHash) {
        jdbc.update(
                "insert into competition.leaderboard_snapshots "
                        + "(id, room_id, scoring_template_version_id, cutoff_at, status, result_hash, created_at) "
                        + "values (?, ?, ?, ?, 'FINAL', ?, ?)",
                snapshotId, roomId, TEMPLATE_ID, utc(AT), resultHash, utc(AT));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private static java.time.OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("e9200000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    private static final UUID OWNER_ID = id(1);
    private static final UUID OUTSIDER_ID = id(2);
    private static final UUID OPERATOR_ID = id(3);
    private static final UUID TEMPLATE_ID = id(4);
    private static final UUID LIVE_ROOM_ID = id(5);
    private static final UUID BACKTEST_ROOM_ID = id(6);
    private static final UUID LIVE_BOT_ID = id(7);
    private static final UUID BACKTEST_BOT_ID = id(8);
    private static final UUID LIVE_PARTICIPATION_ID = id(9);
    private static final UUID BACKTEST_PARTICIPATION_ID = id(10);
    private static final UUID LIVE_PERFORMANCE_ID = id(11);
    private static final UUID BACKTEST_AGGREGATE_ID = id(12);
    private static final UUID LIVE_SNAPSHOT_ID = id(13);
    private static final UUID BACKTEST_SNAPSHOT_ID = id(14);

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(AnonymousLeaderboardJooqAdapter.class)
    static class TestApplication {}
}

package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.competition.ScoringEvidenceNotFoundException;
import com.idea2strategy.backend.application.competition.ScoringEvidenceRequest;
import com.idea2strategy.backend.application.competition.ScoringEvidenceService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
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
@SpringBootTest(classes = ScoringEvidencePersistenceIntegrationTest.TestApplication.class)
class ScoringEvidencePersistenceIntegrationTest {
    private static final UUID OWNER_ID = id(1);
    private static final UUID ROOM_ID = id(2);
    private static final UUID BOT_ID = id(3);
    private static final UUID PARTICIPATION_ID = id(4);
    private static final UUID LOCKED_TEMPLATE_ID = id(5);
    private static final UUID RECALCULATION_TEMPLATE_ID = id(6);
    private static final UUID FEE_ID = id(7);
    private static final UUID BUFFER_ID = id(8);
    private static final UUID SEGMENT_ID = id(9);
    private static final UUID PERFORMANCE_SNAPSHOT_ID = id(10);
    private static final UUID LEADERBOARD_SNAPSHOT_ID = id(11);
    private static final Instant START = Instant.parse("2026-08-02T05:00:00Z");
    private static final Instant CUTOFF = START.plusSeconds(600);

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

    @Autowired ScoringEvidenceJooqAdapter adapter;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        jdbc.update("delete from competition.leaderboard_entries");
        jdbc.update("delete from competition.leaderboard_snapshots");
        jdbc.update("delete from performance.bot_snapshots");
        jdbc.update("delete from competition.live_evaluation_segments");
        jdbc.update("delete from competition.participations");
        jdbc.update("delete from competition.room_rules");
        jdbc.update("delete from competition.rooms");
        jdbc.update("delete from bot.bots");
        jdbc.update("delete from competition.scoring_template_versions where id in (?, ?)",
                LOCKED_TEMPLATE_ID, RECALCULATION_TEMPLATE_ID);
        jdbc.update("delete from trading.fee_policy_versions where id = ?", FEE_ID);
        jdbc.update("delete from trading.buying_power_buffer_policy_versions where id = ?", BUFFER_ID);
        jdbc.update("truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
        jdbc.update("delete from identity.accounts where id = ?", OWNER_ID);

        var publishedAt = START.minusSeconds(3600).atOffset(ZoneOffset.UTC);
        jdbc.update("insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')", OWNER_ID);
        seedTemplate(LOCKED_TEMPLATE_ID, "TOTAL_RETURN", "1", "locked-template-rules", publishedAt);
        seedTemplate(RECALCULATION_TEMPLATE_ID, "TOTAL_RETURN", "2", "recalculation-template-rules", publishedAt);
        jdbc.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'DEFAULT', '1', 20, 'v1', 'fee-rules', ?, ?)",
                FEE_ID, publishedAt, publishedAt);
        jdbc.update(
                "insert into trading.buying_power_buffer_policy_versions "
                        + "(id, policy_code, version, buffer_bps, rounding_rules_version, rules_hash, "
                        + "effective_from, published_at) values (?, 'DEFAULT', '1', 0, 'v1', 'buffer-rules', ?, ?)",
                BUFFER_ID, publishedAt, publishedAt);
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, creator_account_id, name, access_type, status, created_at) "
                        + "values (?, 'LIVE_PAPER', 'USER', ?, 'Scoring evidence room', 'PUBLIC', 'ENDED', ?)",
                ROOM_ID, OWNER_ID, publishedAt);
        jdbc.update(
                "insert into competition.room_rules "
                        + "(room_id, scoring_template_version_id, initial_cash_amount, bot_participation_limit, "
                        + "per_account_bot_limit, eligibility_document, market_scope_document, scoring_parameters, "
                        + "fee_policy_id, slippage_rate_bps, buying_power_buffer_policy_id, precision_rules_version, "
                        + "rules_hash, locked_at) values (?, ?, 100000, 10, 2, '{}'::jsonb, '{}'::jsonb, "
                        + "'{\"minimumTrades\": 1}'::jsonb, ?, 5, ?, 'v1', 'room-rules-hash', ?)",
                ROOM_ID, LOCKED_TEMPLATE_ID, FEE_ID, BUFFER_ID, publishedAt);
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, "
                        + "execution_eligible_from, created_at, edit_sequence, updated_at) "
                        + "values (?, ?, 'BASIC', 'Evidence bot', 'STOPPED', ?, ?, ?, 0, ?)",
                BOT_ID, OWNER_ID, utc(CUTOFF), utc(START), publishedAt, utc(CUTOFF));
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at, "
                        + "evaluation_started_at, evaluation_finished_at) "
                        + "values (?, ?, ?, ?, 'anonymous-evidence', 'COMPLETED', ?, ?, ?)",
                PARTICIPATION_ID, ROOM_ID, BOT_ID, OWNER_ID, publishedAt, utc(START), utc(CUTOFF));
        jdbc.update(
                "insert into competition.live_evaluation_segments "
                        + "(id, participation_id, segment_type, starts_at, ends_at, start_event_sequence, "
                        + "end_event_sequence, initial_state_hash, final_state_hash, source_set_hash, finalized_at) "
                        + "values (?, ?, 'OFFICIAL_EVALUATION', ?, ?, 10, 20, "
                        + "'initial-state-hash', 'final-state-hash', 'source-set-hash', ?)",
                SEGMENT_ID, PARTICIPATION_ID, utc(START), utc(CUTOFF), utc(CUTOFF.plusSeconds(1)));
        jdbc.update(
                "insert into performance.bot_snapshots "
                        + "(id, bot_id, snapshot_type, source_event_sequence, evaluated_at, equity_amount, "
                        + "total_return_pct, max_drawdown_pct, sharpe_ratio, metrics_document, input_hash, "
                        + "calculation_rules_version, snapshot_hash, created_at) "
                        + "values (?, ?, 'LEADERBOARD_CUTOFF', 20, ?, 110000, 10, 4, 1.2, '{}'::jsonb, "
                        + "'performance-input-hash', 'performance-v1', 'performance-snapshot-hash', ?)",
                PERFORMANCE_SNAPSHOT_ID, BOT_ID, utc(CUTOFF), utc(CUTOFF.plusSeconds(1)));
        seedPublishedLeaderboard();
    }

    @Test
    void loadsExactFinalizedLiveSourcesAndLeavesPublishedResultsUntouched() {
        var service = new ScoringEvidenceService(adapter);

        var evidence = service.prepare(request(LOCKED_TEMPLATE_ID));

        assertThat(evidence.source().evaluationSegmentId()).isEqualTo(SEGMENT_ID);
        assertThat(evidence.source().performanceSnapshotId()).isEqualTo(PERFORMANCE_SNAPSHOT_ID);
        assertThat(evidence.source().lockedScoringTemplateVersionId()).isEqualTo(LOCKED_TEMPLATE_ID);
        assertThat(evidence.source().calculationScoringTemplateVersionId()).isEqualTo(LOCKED_TEMPLATE_ID);
        assertThat(evidence.source().sourceSetHash()).isEqualTo("source-set-hash");
        assertThat(evidence.provenanceHash()).matches("sha256:[0-9a-f]{64}");
        assertThat(jdbc.queryForObject(
                "select score from competition.leaderboard_entries where snapshot_id = ? and participation_id = ?",
                BigDecimal.class, LEADERBOARD_SNAPSHOT_ID, PARTICIPATION_ID))
                .isEqualByComparingTo("42.0000000000");
    }

    @Test
    void supportsASeparateRecalculationTemplateWithoutSelectingAnotherPerformanceSource() {
        var service = new ScoringEvidenceService(adapter);

        var original = service.prepare(request(LOCKED_TEMPLATE_ID));
        var recalculation = service.prepare(request(RECALCULATION_TEMPLATE_ID));

        assertThat(recalculation.source().performanceSnapshotId()).isEqualTo(original.source().performanceSnapshotId());
        assertThat(recalculation.source().lockedScoringTemplateVersionId()).isEqualTo(LOCKED_TEMPLATE_ID);
        assertThat(recalculation.source().calculationScoringTemplateVersion()).isEqualTo("2");
        assertThat(recalculation.provenanceHash()).isNotEqualTo(original.provenanceHash());
        assertThat(jdbc.queryForObject("select count(*) from competition.leaderboard_snapshots", Integer.class))
                .isOne();
    }

    @Test
    void rejectsAnUnfinalizedSegmentOrMismatchedSnapshotBoundary() {
        jdbc.update("update competition.live_evaluation_segments set finalized_at = null where id = ?", SEGMENT_ID);
        assertThatThrownBy(() -> adapter.load(request(LOCKED_TEMPLATE_ID)))
                .isInstanceOf(ScoringEvidenceNotFoundException.class);

        jdbc.update("update competition.live_evaluation_segments set finalized_at = ? where id = ?",
                utc(CUTOFF.plusSeconds(1)), SEGMENT_ID);
        jdbc.update("update performance.bot_snapshots set source_event_sequence = 21 where id = ?",
                PERFORMANCE_SNAPSHOT_ID);
        assertThatThrownBy(() -> adapter.load(request(LOCKED_TEMPLATE_ID)))
                .isInstanceOf(ScoringEvidenceNotFoundException.class);
    }

    private void seedTemplate(UUID id, String code, String version, String rulesHash, java.time.OffsetDateTime at) {
        jdbc.update(
                "insert into competition.scoring_template_versions "
                        + "(id, template_code, version, rules_document, rules_hash, published_at) "
                        + "values (?, ?, ?, '{\"kind\": \"SINGLE\"}'::jsonb, ?, ?)",
                id, code, version, rulesHash, at);
    }

    private void seedPublishedLeaderboard() {
        jdbc.update(
                "insert into competition.leaderboard_snapshots "
                        + "(id, room_id, scoring_template_version_id, cutoff_at, status, result_hash, created_at) "
                        + "values (?, ?, ?, ?, 'FINAL', 'published-result-hash', ?)",
                LEADERBOARD_SNAPSHOT_ID, ROOM_ID, LOCKED_TEMPLATE_ID, utc(CUTOFF), utc(CUTOFF.plusSeconds(2)));
        jdbc.update(
                "insert into competition.leaderboard_entries "
                        + "(snapshot_id, participation_id, performance_snapshot_id, rank, eligibility_status, "
                        + "score, tie_break_document, calculation_document) "
                        + "values (?, ?, ?, 1, 'ELIGIBLE', 42, '{}'::jsonb, '{}'::jsonb)",
                LEADERBOARD_SNAPSHOT_ID, PARTICIPATION_ID, PERFORMANCE_SNAPSHOT_ID);
    }

    private static ScoringEvidenceRequest request(UUID calculationTemplateId) {
        return new ScoringEvidenceRequest(
                PARTICIPATION_ID, SEGMENT_ID, PERFORMANCE_SNAPSHOT_ID, calculationTemplateId);
    }

    private static java.time.OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("b7000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ScoringEvidenceJooqAdapter.class)
    static class TestApplication {}
}

package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.competition.AnonymousLeaderboardQuery;
import com.idea2strategy.backend.application.competition.InvalidLeaderboardCursorException;
import com.idea2strategy.backend.application.competition.LeaderboardAccessException;
import com.idea2strategy.backend.application.competition.LeaderboardAuthenticationException;
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
@SpringBootTest(classes = AnonymousLeaderboardPersistenceIntegrationTest.TestApplication.class)
class AnonymousLeaderboardPersistenceIntegrationTest {
    private static final UUID VIEWER_ID = id(1);
    private static final UUID OTHER_ID = id(2);
    private static final UUID OUTSIDER_ID = id(50);
    private static final UUID INACTIVE_ID = id(51);
    private static final UUID ROOM_ID = id(3);
    private static final UUID EMPTY_ROOM_ID = id(4);
    private static final UUID SECRET_ROOM_ID = id(5);
    private static final UUID SCORING_ID = id(6);
    private static final UUID OLD_SNAPSHOT_ID = id(7);
    private static final UUID SNAPSHOT_ID = id(8);
    private static final Instant CUTOFF = Instant.parse("2026-08-02T05:00:00Z");

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
    private AnonymousLeaderboardJooqAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        jdbc.update("delete from competition.leaderboard_entries");
        jdbc.update("delete from competition.leaderboard_snapshots");
        jdbc.update("delete from competition.room_events");
        jdbc.update("delete from performance.bot_snapshots");
        jdbc.update("delete from competition.participations");
        jdbc.update("delete from competition.rooms");
        jdbc.update("delete from bot.bots");
        jdbc.update("delete from competition.scoring_template_versions where id = ?", SCORING_ID);
        jdbc.execute("truncate table identity.account_lifecycle_command_receipts, identity.account_lifecycle_events cascade");
        jdbc.update("delete from identity.accounts where id in (?, ?, ?)", VIEWER_ID, OTHER_ID, OUTSIDER_ID);
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status) "
                        + "values (?, 'ACTIVE'), (?, 'ACTIVE'), (?, 'ACTIVE')",
                VIEWER_ID, OTHER_ID, OUTSIDER_ID);
        jdbc.update(
                "insert into competition.scoring_template_versions "
                        + "(id, template_code, version, rules_document, rules_hash, published_at) "
                        + "values (?, 'TOTAL_RETURN', 'v21', '{}'::jsonb, 'scoring-e21', ?)",
                SCORING_ID, CUTOFF.minusSeconds(3600).atOffset(ZoneOffset.UTC));
        seedRoom(ROOM_ID, "PUBLIC");
        seedRoom(EMPTY_ROOM_ID, "PUBLIC");
        seedRoom(SECRET_ROOM_ID, "SECRET");
    }

    @Test
    void readsTheLatestSnapshotInStableBotOrderAndRevealsEvidenceOnlyForViewerOwnedBots() {
        seedLeaderboard();

        var anonymous = adapter.query(query(ROOM_ID, OUTSIDER_ID, null, null, 10));
        assertThat(anonymous.snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(anonymous.snapshotStatus()).isEqualTo("FINAL");
        assertThat(anonymous.rows())
                .extracting(row -> row.item().anonymousAlias())
                .containsExactly("alpha", "beta", "gamma");
        assertThat(anonymous.rows())
                .allSatisfy(row -> assertThat(row.item().viewerEvidence()).isNull());
        assertThat(anonymous.rows().getFirst().item())
                .satisfies(item -> {
                    assertThat(item.totalReturnPct()).isEqualByComparingTo("12.50000000");
                    assertThat(item.maxDrawdownPct()).isEqualByComparingTo("3.25000000");
                    assertThat(item.sharpeRatio()).isEqualByComparingTo("1.75000000");
                });

        var owned = adapter.query(query(ROOM_ID, VIEWER_ID, null, null, 10));
        assertThat(owned.rows().get(0).item().viewerEvidence())
                .satisfies(evidence -> {
                    assertThat(evidence.botId()).isEqualTo(id(20));
                    assertThat(evidence.participationId()).isEqualTo(id(10));
                    assertThat(evidence.performanceSnapshotId()).isEqualTo(id(30));
                    assertThat(evidence.eligibilityReasonCode()).isEqualTo("OWNER_ONLY_REASON");
                });
        assertThat(owned.rows().get(1).item().viewerEvidence()).isNull();
        assertThat(owned.rows().get(2).item().viewerEvidence()).isNotNull();
    }

    @Test
    void comparesEveryViewerOwnedBotWithoutRequiringACommonSymbolOrLeakingOtherBots() {
        seedLeaderboard();
        jdbc.update(
                "update competition.leaderboard_entries set eligibility_status = 'INELIGIBLE_PRIVATE' "
                        + "where snapshot_id = ? and participation_id = ?",
                SNAPSHOT_ID, id(12));

        var owned = adapter.queryOwned(query(ROOM_ID, VIEWER_ID, null, null, 10));

        assertThat(owned.rows())
                .extracting(row -> row.item().anonymousAlias())
                .containsExactly("alpha", "gamma");
        assertThat(owned.rows()).allSatisfy(row -> {
            assertThat(row.item().viewerEvidence()).isNotNull();
            assertThat(row.item().viewerEvidence().botId()).isIn(id(20), id(22));
        });
        assertThat(owned.rows().getLast().item().eligibilityStatus()).isEqualTo("INELIGIBLE_PRIVATE");
    }

    @Test
    void keepsOwnedPaginationViewerScopedAndRejectsAnAnonymousLeaderboardCursor() {
        seedLeaderboard();
        var first = adapter.queryOwned(query(ROOM_ID, VIEWER_ID, null, null, 1));
        var continued = adapter.queryOwned(query(
                ROOM_ID, VIEWER_ID, SNAPSHOT_ID,
                new Cursor(first.rows().getFirst().item().rank(), first.rows().getFirst().cursorAnchor()), 10));

        assertThat(continued.rows())
                .extracting(row -> row.item().anonymousAlias())
                .containsExactly("gamma");

        String anonymousAnchor = adapter.query(query(ROOM_ID, VIEWER_ID, null, null, 1))
                .rows().getFirst().cursorAnchor();
        assertThatThrownBy(() -> adapter.queryOwned(query(
                        ROOM_ID, VIEWER_ID, SNAPSHOT_ID, new Cursor(1, anonymousAnchor), 10)))
                .isInstanceOf(InvalidLeaderboardCursorException.class)
                .hasMessageContaining("anchor");
    }

    @Test
    void continuesAfterTheExactJointRankBotWithoutMergingAnOwnersBots() {
        seedLeaderboard();
        String firstAnchor = adapter.query(query(ROOM_ID, VIEWER_ID, null, null, 10))
                .rows().getFirst().cursorAnchor();

        var page = adapter.query(query(ROOM_ID, VIEWER_ID, SNAPSHOT_ID, new Cursor(1, firstAnchor), 10));

        assertThat(page.rows())
                .extracting(row -> row.item().rank())
                .containsExactly(1, 2);
    }

    @Test
    void exposesOnlyPublicEligibilityUnlessTheViewerOwnsTheIneligibleBot() {
        seedLeaderboard();
        jdbc.update(
                "update competition.leaderboard_entries set eligibility_status = 'INELIGIBLE_PRIVATE', "
                        + "eligibility_reason_code = 'PRIVATE_OTHER_REASON' "
                        + "where snapshot_id = ? and participation_id = ?",
                SNAPSHOT_ID, id(11));

        var outsider = adapter.query(query(ROOM_ID, OUTSIDER_ID, null, null, 10));
        assertThat(outsider.rows())
                .extracting(row -> row.item().anonymousAlias())
                .containsExactly("alpha", "gamma");
        assertThat(outsider.rows())
                .allSatisfy(row -> assertThat(row.item().eligibilityStatus()).isEqualTo("ELIGIBLE"));

        var owner = adapter.query(query(ROOM_ID, OTHER_ID, null, null, 10));
        assertThat(owner.rows())
                .filteredOn(row -> row.item().anonymousAlias().equals("beta"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.item().eligibilityStatus()).isEqualTo("INELIGIBLE_PRIVATE");
                    assertThat(row.item().viewerEvidence().eligibilityReasonCode())
                            .isEqualTo("PRIVATE_OTHER_REASON");
                });
    }

    @Test
    void resolvesAnIssuedAnchorFromImmutableMembershipAfterItsParticipationWithdraws() {
        seedLeaderboard();
        String issuedAnchor = adapter.query(query(ROOM_ID, VIEWER_ID, null, null, 10))
                .rows().getFirst().cursorAnchor();
        jdbc.update(
                "update competition.participations set status = 'WITHDRAWN', withdrawn_at = ? where id = ?",
                CUTOFF.atOffset(ZoneOffset.UTC), id(10));

        var continued = adapter.query(query(
                ROOM_ID, VIEWER_ID, SNAPSHOT_ID, new Cursor(1, issuedAnchor), 10));

        assertThat(continued.rows())
                .extracting(row -> row.item().anonymousAlias())
                .containsExactly("beta", "gamma");
    }

    @Test
    void allowsPublicAndValidSecretParticipantsButRejectsOtherSecretViewers() {
        seedSnapshot(SECRET_ROOM_ID, id(40), "PUBLISHED", CUTOFF);
        seedBotParticipation(SECRET_ROOM_ID, VIEWER_ID, id(41), id(42), "secret-member", "REGISTERED");

        assertThat(adapter.query(query(ROOM_ID, VIEWER_ID, null, null, 10))).isNotNull();
        assertThat(adapter.query(query(SECRET_ROOM_ID, VIEWER_ID, null, null, 10)).snapshotId())
                .isEqualTo(id(40));
        assertThat(adapter.queryOwned(query(SECRET_ROOM_ID, VIEWER_ID, null, null, 10)).snapshotId())
                .isEqualTo(id(40));
        assertThatThrownBy(() -> adapter.query(query(SECRET_ROOM_ID, null, null, null, 10)))
                .isInstanceOf(LeaderboardAuthenticationException.class);
        assertThatThrownBy(() -> adapter.query(query(SECRET_ROOM_ID, OUTSIDER_ID, null, null, 10)))
                .isInstanceOf(LeaderboardAccessException.class);
        assertThatThrownBy(() -> adapter.queryOwned(query(SECRET_ROOM_ID, OUTSIDER_ID, null, null, 10)))
                .isInstanceOf(LeaderboardAccessException.class);
        jdbc.update(
                "update competition.participations set status = 'WITHDRAWN', withdrawn_at = ? where id = ?",
                CUTOFF.atOffset(ZoneOffset.UTC), id(42));
        assertThatThrownBy(() -> adapter.query(query(SECRET_ROOM_ID, VIEWER_ID, null, null, 10)))
                .isInstanceOf(LeaderboardAccessException.class);
    }

    @Test
    void returnsEmptyWhenNoPublishedOrFinalSnapshotExists() {
        assertThat(adapter.query(query(EMPTY_ROOM_ID, VIEWER_ID, null, null, 10)).rows()).isEmpty();
        assertThat(adapter.query(query(EMPTY_ROOM_ID, VIEWER_ID, null, null, 10)).snapshotId()).isNull();
    }

    @Test
    void rejectsInactiveAccountsInvalidCursorsAndCursorsFromAnotherRoom() {
        seedLeaderboard();
        jdbc.update(
                "insert into identity.accounts (id, lifecycle_status, dormant_at) values (?, 'DORMANT', ?)",
                INACTIVE_ID,
                CUTOFF.atOffset(ZoneOffset.UTC));
        assertThatThrownBy(() -> adapter.query(query(ROOM_ID, INACTIVE_ID, null, null, 10)))
                .isInstanceOf(LeaderboardAuthenticationException.class);
        assertThatThrownBy(() -> adapter.query(query(
                        ROOM_ID, VIEWER_ID, SNAPSHOT_ID,
                        new Cursor(1, "sha256:" + "0".repeat(64)), 10)))
                .isInstanceOf(InvalidLeaderboardCursorException.class)
                .hasMessageContaining("anchor");
        String validAnchor = adapter.query(query(ROOM_ID, VIEWER_ID, null, null, 10))
                .rows().getFirst().cursorAnchor();
        assertThatThrownBy(() -> adapter.query(query(
                        EMPTY_ROOM_ID, VIEWER_ID, SNAPSHOT_ID, new Cursor(1, validAnchor), 10)))
                .isInstanceOf(InvalidLeaderboardCursorException.class)
                .hasMessageContaining("snapshot");
    }

    @Test
    void suppressesInvalidatedInsufficientAndWithdrawnOrExpelledEvidence() {
        seedLeaderboard();
        jdbc.update(
                "update competition.participations set status = 'WITHDRAWN', withdrawn_at = ? where id = ?",
                CUTOFF.atOffset(ZoneOffset.UTC), id(11));
        jdbc.update(
                "update competition.participations set status = 'EXPELLED', expelled_at = ? where id = ?",
                CUTOFF.atOffset(ZoneOffset.UTC), id(12));
        assertThat(adapter.query(query(ROOM_ID, VIEWER_ID, null, null, 10)).rows())
                .extracting(row -> row.item().anonymousAlias())
                .containsExactly("alpha");

        jdbc.update("update competition.rooms set status = 'INVALIDATED' where id = ?", ROOM_ID);
        assertThat(adapter.query(query(ROOM_ID, VIEWER_ID, null, null, 10)).rows()).isEmpty();
        assertThat(adapter.queryOwned(query(ROOM_ID, VIEWER_ID, null, null, 10)).rows()).isEmpty();
        jdbc.update("update competition.rooms set status = 'CANCELLED' where id = ?", ROOM_ID);
        assertThat(adapter.query(query(ROOM_ID, VIEWER_ID, null, null, 10)).rows()).isEmpty();
        jdbc.update("update competition.rooms set status = 'ENDED' where id = ?", ROOM_ID);
        jdbc.update(
                "insert into competition.room_events "
                        + "(id, room_id, event_sequence, event_type, resulting_status, reason_code, "
                        + "occurred_at, payload_document) values (?, ?, 1, 'INSUFFICIENT_PARTICIPATION', "
                        + "'ENDED', 'INSUFFICIENT_PARTICIPATION', ?, '{}'::jsonb)",
                id(51), ROOM_ID, CUTOFF.atOffset(ZoneOffset.UTC));
        assertThat(adapter.query(query(ROOM_ID, VIEWER_ID, null, null, 10)).rows()).isEmpty();
    }

    private void seedLeaderboard() {
        seedSnapshot(ROOM_ID, OLD_SNAPSHOT_ID, "PUBLISHED", CUTOFF.minusSeconds(60));
        seedSnapshot(ROOM_ID, SNAPSHOT_ID, "FINAL", CUTOFF);
        seedEntry(VIEWER_ID, id(20), id(10), id(30), "alpha", 1, true, "OWNER_ONLY_REASON");
        seedEntry(OTHER_ID, id(21), id(11), id(31), "beta", 1, true, "PRIVATE_OTHER_REASON");
        seedEntry(VIEWER_ID, id(22), id(12), id(32), "gamma", 2, false, null);
    }

    private void seedEntry(
            UUID ownerId,
            UUID botId,
            UUID participationId,
            UUID performanceId,
            String alias,
            int rank,
            boolean joint,
            String reason) {
        seedBotParticipation(ROOM_ID, ownerId, botId, participationId, alias, "REGISTERED");
        jdbc.update(
                "update competition.participations set status = 'COMPLETED', evaluation_started_at = ?, "
                        + "evaluation_finished_at = ? "
                        + "where id = ?",
                CUTOFF.minusSeconds(300).atOffset(ZoneOffset.UTC), CUTOFF.atOffset(ZoneOffset.UTC), participationId);
        jdbc.update(
                "insert into performance.bot_snapshots "
                        + "(id, bot_id, snapshot_type, source_event_sequence, evaluated_at, equity_amount, "
                        + "total_return_pct, max_drawdown_pct, sharpe_ratio, metrics_document, input_hash, "
                        + "calculation_rules_version, snapshot_hash, created_at) "
                        + "values (?, ?, 'LEADERBOARD_CUTOFF', 10, ?, 112500, 12.5, 3.25, 1.75, '{}'::jsonb, "
                        + "?, 'live-performance.v1', ?, ?)",
                performanceId, botId, CUTOFF.atOffset(ZoneOffset.UTC),
                "input-" + performanceId, "snapshot-" + performanceId, CUTOFF.atOffset(ZoneOffset.UTC));
        jdbc.update(
                "insert into competition.leaderboard_entries "
                        + "(snapshot_id, participation_id, performance_snapshot_id, rank, is_joint_rank, "
                        + "eligibility_status, eligibility_reason_code, score, tie_break_document, "
                        + "calculation_document) values (?, ?, ?, ?, ?, 'ELIGIBLE', ?, ?, "
                        + "'{\"privateTie\":true}'::jsonb, '{\"privateCalculation\":true}'::jsonb)",
                SNAPSHOT_ID, participationId, performanceId, rank, joint, reason, 100 - rank);
    }

    private void seedBotParticipation(
            UUID roomId, UUID ownerId, UUID botId, UUID participationId, String alias, String status) {
        var at = CUTOFF.minusSeconds(600).atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into bot.bots "
                        + "(id, owner_account_id, mode, name, lifecycle_status, lifecycle_changed_at, created_at, "
                        + "execution_eligible_from, updated_at) values (?, ?, 'BASIC', ?, 'RUNNING', ?, ?, ?, ?)",
                botId, ownerId, alias, at, at, at, at);
        jdbc.update(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at) "
                        + "values (?, ?, ?, ?, ?, ?::competition.participation_status, ?)",
                participationId, roomId, botId, ownerId, alias, status, at);
    }

    private void seedRoom(UUID roomId, String access) {
        jdbc.update(
                "insert into competition.rooms "
                        + "(id, competition_type, organizer_type, creator_account_id, name, access_type, "
                        + "status, created_at) values (?, 'LIVE_PAPER', 'USER', ?, ?, "
                        + "?::competition.room_access_type, 'ENDED', ?)",
                roomId, VIEWER_ID, "room-" + roomId, access, CUTOFF.minusSeconds(3600).atOffset(ZoneOffset.UTC));
    }

    private void seedSnapshot(UUID roomId, UUID snapshotId, String status, Instant cutoff) {
        jdbc.update(
                "insert into competition.leaderboard_snapshots "
                        + "(id, room_id, scoring_template_version_id, cutoff_at, status, result_hash, created_at) "
                        + "values (?, ?, ?, ?, ?::competition.leaderboard_status, ?, ?)",
                snapshotId, roomId, SCORING_ID, cutoff.atOffset(ZoneOffset.UTC), status,
                "result-" + snapshotId, cutoff.atOffset(ZoneOffset.UTC));
    }

    private AnonymousLeaderboardQuery query(
            UUID roomId, UUID viewerId, UUID snapshotId, Cursor cursor, int limit) {
        return new AnonymousLeaderboardQuery(
                roomId, viewerId, snapshotId,
                cursor == null ? null : cursor.rank(),
                cursor == null ? null : cursor.anchor(),
                limit);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("92000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    private record Cursor(int rank, String anchor) {}

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(AnonymousLeaderboardJooqAdapter.class)
    static class TestApplication {}
}

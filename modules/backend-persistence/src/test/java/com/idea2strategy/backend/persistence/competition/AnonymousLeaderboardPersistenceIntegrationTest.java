package com.idea2strategy.backend.persistence.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.competition.AnonymousLeaderboardQuery;
import com.idea2strategy.backend.application.competition.InvalidLeaderboardCursorException;
import com.idea2strategy.backend.application.competition.LeaderboardAccessException;
import com.idea2strategy.backend.application.competition.LeaderboardAuthenticationException;
import com.idea2strategy.backend.application.competition.RoomLeaderboardQuery;
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
    private static final UUID FEE_POLICY_ID = id(60);
    private static final UUID BUFFER_POLICY_ID = id(61);
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
    private RoomLeaderboardJooqAdapter roomLeaderboardAdapter;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        jdbc.update("delete from competition.room_final_access_grants");
        jdbc.update("delete from competition.leaderboard_entries");
        jdbc.update("delete from competition.leaderboard_snapshots");
        jdbc.update("delete from competition.room_events");
        jdbc.update("delete from performance.bot_snapshots");
        jdbc.update("delete from competition.participations");
        jdbc.update("delete from competition.live_room_rules where room_id in (?, ?, ?)", ROOM_ID, EMPTY_ROOM_ID, SECRET_ROOM_ID);
        jdbc.update("delete from competition.room_rules where room_id in (?, ?, ?)", ROOM_ID, EMPTY_ROOM_ID, SECRET_ROOM_ID);
        jdbc.update("delete from competition.room_schedules where room_id in (?, ?, ?)", ROOM_ID, EMPTY_ROOM_ID, SECRET_ROOM_ID);
        jdbc.update("delete from competition.rooms");
        jdbc.update("delete from bot.bots");
        jdbc.update("delete from trading.fee_policy_versions where id = ?", FEE_POLICY_ID);
        jdbc.update("delete from trading.buying_power_buffer_policy_versions where id = ?", BUFFER_POLICY_ID);
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
        jdbc.update(
                "insert into trading.fee_policy_versions "
                        + "(id, policy_code, version, fee_rate_bps, calculation_rules_version, "
                        + "rules_hash, effective_from, published_at) "
                        + "values (?, 'E29', 'v1', 20, 'v1', 'e29-fee', ?, ?)",
                FEE_POLICY_ID, CUTOFF.minusSeconds(7200).atOffset(ZoneOffset.UTC),
                CUTOFF.minusSeconds(7200).atOffset(ZoneOffset.UTC));
        jdbc.update(
                "insert into trading.buying_power_buffer_policy_versions "
                        + "(id, policy_code, version, buffer_bps, rounding_rules_version, "
                        + "rules_hash, effective_from, published_at) "
                        + "values (?, 'E29', 'v1', 0, 'v1', 'e29-buffer', ?, ?)",
                BUFFER_POLICY_ID, CUTOFF.minusSeconds(7200).atOffset(ZoneOffset.UTC),
                CUTOFF.minusSeconds(7200).atOffset(ZoneOffset.UTC));
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
                "update competition.leaderboard_entries set eligibility_status = 'INELIGIBLE_PRIVATE', "
                        + "eligibility_reason_code = 'COVERAGE_BELOW_MINIMUM', rank = null, score = null "
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
        assertThat(owned.rows().getLast().item().rank()).isNull();
        assertThat(owned.rows().getLast().item().score()).isNull();
    }

    @Test
    void keepsOwnedPaginationViewerScopedAndRejectsAnAnonymousLeaderboardCursor() {
        seedLeaderboard();
        jdbc.update(
                "update competition.leaderboard_entries set eligibility_status = 'INELIGIBLE_PRIVATE', "
                        + "eligibility_reason_code = 'COVERAGE_BELOW_MINIMUM', rank = null, score = null "
                        + "where snapshot_id = ? and participation_id = ?",
                SNAPSHOT_ID, id(12));
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
    void usesFrozenFinalSecretGrantsInsteadOfMutableParticipationStatus() {
        seedSnapshot(SECRET_ROOM_ID, id(40), "FINAL", CUTOFF);
        seedBotParticipation(SECRET_ROOM_ID, VIEWER_ID, id(41), id(42), "secret-member", "REGISTERED");
        seedRoomDetails(SECRET_ROOM_ID);
        jdbc.update(
                "insert into competition.room_final_access_grants "
                        + "(room_id, account_id, snapshot_id, eligibility_basis, granted_at) "
                        + "values (?, ?, ?, 'ACTIVE_PARTICIPANT', ?)",
                SECRET_ROOM_ID, VIEWER_ID, id(40), CUTOFF.atOffset(ZoneOffset.UTC));

        assertThat(adapter.query(query(ROOM_ID, VIEWER_ID, null, null, 10))).isNotNull();
        assertThat(adapter.query(query(SECRET_ROOM_ID, VIEWER_ID, null, null, 10)).snapshotId())
                .isEqualTo(id(40));
        assertThat(adapter.queryOwned(query(SECRET_ROOM_ID, VIEWER_ID, null, null, 10)).snapshotId())
                .isEqualTo(id(40));
        assertThat(roomLeaderboardAdapter.queryRoomLeaderboard(new RoomLeaderboardQuery(
                SECRET_ROOM_ID, VIEWER_ID, null, null, null, 10, null, null, 10)).room().roomId())
                .isEqualTo(SECRET_ROOM_ID);
        assertThatThrownBy(() -> adapter.query(query(SECRET_ROOM_ID, null, null, null, 10)))
                .isInstanceOf(LeaderboardAuthenticationException.class);
        assertThatThrownBy(() -> adapter.query(query(SECRET_ROOM_ID, OUTSIDER_ID, null, null, 10)))
                .isInstanceOf(LeaderboardAccessException.class);
        assertThatThrownBy(() -> adapter.queryOwned(query(SECRET_ROOM_ID, OUTSIDER_ID, null, null, 10)))
                .isInstanceOf(LeaderboardAccessException.class);
        assertThatThrownBy(() -> roomLeaderboardAdapter.queryRoomLeaderboard(new RoomLeaderboardQuery(
                        SECRET_ROOM_ID, OUTSIDER_ID, null, null, null, 10, null, null, 10)))
                .isInstanceOf(LeaderboardAccessException.class);
        jdbc.update(
                "update competition.participations set status = 'WITHDRAWN', withdrawn_at = ? where id = ?",
                CUTOFF.atOffset(ZoneOffset.UTC), id(42));
        assertThat(adapter.query(query(SECRET_ROOM_ID, VIEWER_ID, null, null, 10)).snapshotId())
                .isEqualTo(id(40));
    }

    @Test
    void closesEndedRoomQueriesAtTheExactOneYearBoundaryAndRechecksCursors() {
        seedLeaderboard();
        var first = adapter.query(query(ROOM_ID, VIEWER_ID, null, null, 1));
        jdbc.update(
                "update competition.rooms set ended_at = current_timestamp - interval '1 year' where id = ?",
                ROOM_ID);

        assertThat(adapter.query(query(ROOM_ID, VIEWER_ID, null, null, 10)).snapshotId()).isNull();
        assertThat(adapter.query(query(
                ROOM_ID, VIEWER_ID, SNAPSHOT_ID,
                new Cursor(first.rows().getFirst().item().rank(), first.rows().getFirst().cursorAnchor()), 10
        )).snapshotId()).isNull();

        jdbc.update(
                "update competition.rooms set ended_at = "
                        + "current_timestamp - interval '1 year' + interval '1 second' where id = ?",
                ROOM_ID);
        assertThat(adapter.query(query(ROOM_ID, VIEWER_ID, null, null, 10)).snapshotId())
                .isEqualTo(SNAPSHOT_ID);
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

    @Test
    void combinesRoomViewerAndBothLeaderboardBlocksOnOneFinalSnapshot() {
        seedLeaderboard();
        seedRoomDetails(ROOM_ID);

        var result = roomLeaderboardAdapter.queryRoomLeaderboard(new RoomLeaderboardQuery(
                ROOM_ID, VIEWER_ID, null, null, null, 10, null, null, 10));

        assertThat(result.room())
                .satisfies(room -> {
                    assertThat(room.roomId()).isEqualTo(ROOM_ID);
                    assertThat(room.status()).isEqualTo("ENDED");
                    assertThat(room.scoringTemplateVersionId()).isEqualTo(SCORING_ID);
                });
        assertThat(result.leaderboard().snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(result.ownedBots().snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(result.leaderboard().rows())
                .extracting(row -> row.item().anonymousAlias())
                .containsExactly("alpha", "beta", "gamma");
        assertThat(result.ownedBots().rows())
                .extracting(row -> row.item().anonymousAlias())
                .containsExactly("alpha", "gamma");
        assertThat(result.viewerParticipations())
                .extracting(state -> state.anonymousAlias())
                .containsExactly("alpha", "gamma");
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
                        + "status, created_at, ended_at) values (?, 'LIVE_PAPER', 'USER', ?, ?, "
                        + "?::competition.room_access_type, 'ENDED', ?, ?)",
                roomId, VIEWER_ID, "room-" + roomId, access,
                CUTOFF.minusSeconds(3600).atOffset(ZoneOffset.UTC), CUTOFF.atOffset(ZoneOffset.UTC));
    }

    private void seedRoomDetails(UUID roomId) {
        var at = CUTOFF.atOffset(ZoneOffset.UTC);
        jdbc.update(
                "insert into competition.room_schedules "
                        + "(room_id, recruitment_opens_at, participation_opens_at, evaluation_starts_at, "
                        + "participation_closes_at, evaluation_ends_at, finalization_deadline_at, timezone_name) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'UTC')",
                roomId, CUTOFF.minusSeconds(7200).atOffset(ZoneOffset.UTC),
                CUTOFF.minusSeconds(7200).atOffset(ZoneOffset.UTC),
                CUTOFF.minusSeconds(3600).atOffset(ZoneOffset.UTC),
                CUTOFF.minusSeconds(3600).atOffset(ZoneOffset.UTC), at,
                CUTOFF.plusSeconds(3600).atOffset(ZoneOffset.UTC));
        jdbc.update(
                "insert into competition.room_rules "
                        + "(room_id, scoring_template_version_id, initial_cash_amount, currency_code, "
                        + "bot_participation_limit, per_account_bot_limit, eligibility_document, "
                        + "market_scope_document, scoring_parameters, fee_policy_id, slippage_rate_bps, "
                        + "buying_power_buffer_policy_id, precision_rules_version, rules_hash, locked_at) "
                        + "values (?, ?, 100000, 'USD', 10, 3, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, "
                        + "?, 5, ?, "
                        + "'1.0', 'integrated-rules', ?)",
                roomId, SCORING_ID, FEE_POLICY_ID, BUFFER_POLICY_ID,
                CUTOFF.minusSeconds(7200).atOffset(ZoneOffset.UTC));
        jdbc.update(
                "insert into competition.live_room_rules "
                        + "(room_id, stopped_bot_slot_policy, minimum_operation_seconds, minimum_fill_count) "
                        + "values (?, 'COUNT_UNTIL_END', 0, 0)",
                roomId);
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
    @Import({AnonymousLeaderboardJooqAdapter.class, RoomLeaderboardJooqAdapter.class})
    static class TestApplication {}
}

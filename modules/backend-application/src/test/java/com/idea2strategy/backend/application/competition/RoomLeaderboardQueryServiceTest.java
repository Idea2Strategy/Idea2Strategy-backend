package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RoomLeaderboardQueryServiceTest {
    private static final UUID ROOM_ID = id(1);
    private static final UUID VIEWER_ID = id(2);
    private static final UUID SNAPSHOT_ID = id(3);
    private static final Instant CUTOFF = Instant.parse("2026-08-02T05:00:00Z");

    @Test
    void combinesBothBlocksOnOneSnapshotAndKeepsOwnedEvidenceOutOfThePublicBlock() {
        var captured = new AtomicReference<RoomLeaderboardQuery>();
        var service = new RoomLeaderboardQueryService(query -> {
            captured.set(query);
            return result();
        }, () -> VIEWER_ID);

        var first = service.query(ROOM_ID, null, 1, null, 1);

        assertThat(first.room().roomId()).isEqualTo(ROOM_ID);
        assertThat(first.viewerParticipations()).extracting(ViewerParticipationState::anonymousAlias)
                .containsExactly("alpha");
        assertThat(first.leaderboard().snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(first.myBots().snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(first.leaderboard().items()).singleElement()
                .satisfies(item -> assertThat(item.viewerEvidence()).isNull());
        assertThat(first.myBots().items()).singleElement()
                .satisfies(item -> assertThat(item.evidence().botId()).isEqualTo(id(20)));
        assertThat(first.leaderboard().nextCursor()).isNotBlank();
        assertThat(first.myBots().nextCursor()).isNotBlank();

        service.query(
                ROOM_ID, first.leaderboard().nextCursor(), 1, first.myBots().nextCursor(), 1);
        assertThat(captured.get().snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(captured.get().leaderboardAfterRank()).isEqualTo(1);
        assertThat(captured.get().ownedAfterRank()).isEqualTo(1);
    }

    @Test
    void bindsCursorsToBlockRoomAndViewerAndSupportsUnrankedOwnedRows() {
        var service = new RoomLeaderboardQueryService(query -> result(), () -> VIEWER_ID);
        var first = service.query(ROOM_ID, null, 1, null, 1);

        assertThatThrownBy(() -> service.query(
                        ROOM_ID, first.myBots().nextCursor(), 1, null, 1))
                .isInstanceOf(InvalidLeaderboardCursorException.class);
        assertThatThrownBy(() -> new RoomLeaderboardQueryService(query -> result(), () -> id(99))
                        .query(ROOM_ID, first.leaderboard().nextCursor(), 1, null, 1))
                .isInstanceOf(InvalidLeaderboardCursorException.class);
        assertThatThrownBy(() -> service.query(
                        id(98), first.leaderboard().nextCursor(), 1, null, 1))
                .isInstanceOf(InvalidLeaderboardCursorException.class);

        var full = service.query(ROOM_ID, null, 2, null, 2);
        assertThat(full.myBots().items().getLast().rank()).isNull();
    }

    @Test
    void requiresAuthenticationAndRejectsMismatchedSnapshotCursors() {
        var anonymous = new RoomLeaderboardQueryService(query -> result(), () -> null);
        assertThatThrownBy(() -> anonymous.query(ROOM_ID, null, 20, null, 20))
                .isInstanceOf(LeaderboardAuthenticationException.class);

        var firstService = new RoomLeaderboardQueryService(query -> result(), () -> VIEWER_ID);
        var first = firstService.query(ROOM_ID, null, 1, null, 1);
        var otherSnapshotService = new RoomLeaderboardQueryService(
                query -> resultWithSnapshot(id(77)), () -> VIEWER_ID);
        var other = otherSnapshotService.query(ROOM_ID, null, 1, null, 1);
        assertThatThrownBy(() -> firstService.query(
                        ROOM_ID, first.leaderboard().nextCursor(), 1, other.myBots().nextCursor(), 1))
                .isInstanceOf(InvalidLeaderboardCursorException.class)
                .hasMessageContaining("snapshots");
    }

    private static RoomLeaderboardQueryResult result() {
        return resultWithSnapshot(SNAPSHOT_ID);
    }

    private static RoomLeaderboardQueryResult resultWithSnapshot(UUID snapshotId) {
        var evidence = new OwnedLeaderboardEvidence(id(20), id(10), id(30), null, "OWNER_ONLY");
        var publicRows = List.of(
                row(snapshotId, 1, "alpha", evidence),
                row(snapshotId, 2, "beta", null));
        var ownedRows = List.of(
                row(snapshotId, 1, "alpha", evidence),
                row(snapshotId, null, "gamma", evidence));
        return new RoomLeaderboardQueryResult(
                new RoomLeaderboardSummary(
                        ROOM_ID, "room", "LIVE_PAPER", "USER", "PUBLIC", "ENDED",
                        CUTOFF.minusSeconds(3600), CUTOFF, CUTOFF, id(4), "rules-hash"),
                List.of(new ViewerParticipationState(
                        "alpha", "COMPLETED", CUTOFF.minusSeconds(7200),
                        CUTOFF.minusSeconds(3600), CUTOFF)),
                new LeaderboardQueryResult(snapshotId, "FINAL", CUTOFF, publicRows),
                new LeaderboardQueryResult(snapshotId, "FINAL", CUTOFF, ownedRows));
    }

    private static LeaderboardQueryRow row(
            UUID snapshotId, Integer rank, String alias, OwnedLeaderboardEvidence evidence) {
        int suffix = Math.abs(alias.hashCode());
        return new LeaderboardQueryRow(
                "sha256:" + String.format("%064x", suffix),
                new AnonymousLeaderboardItem(
                        rank, false, alias, rank == null ? null : BigDecimal.TEN,
                        rank == null ? "INELIGIBLE_PRIVATE" : "ELIGIBLE",
                        BigDecimal.valueOf(100), BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, evidence));
    }

    private static UUID id(int suffix) {
        return UUID.fromString("93000000-0000-4000-8000-" + String.format("%012d", suffix));
    }
}

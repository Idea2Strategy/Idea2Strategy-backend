package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OwnedBotComparisonQueryServiceTest {
    private static final UUID ROOM_ID = id(1);
    private static final UUID SNAPSHOT_ID = id(2);
    private static final UUID ACCOUNT_ID = id(3);

    @Test
    void keepsTheSnapshotAndReturnsOnlyRowsWithViewerOwnedEvidence() {
        var request = new AtomicReference<AnonymousLeaderboardQuery>();
        OwnedBotComparisonQueryPort port = query -> {
            request.set(query);
            return new LeaderboardQueryResult(
                    SNAPSHOT_ID,
                    "FINAL",
                    Instant.parse("2026-08-02T00:00:00Z"),
                    List.of(row(1, 11), row(2, 12), row(3, 13)));
        };
        var service = new OwnedBotComparisonQueryService(port, () -> ACCOUNT_ID);

        var first = service.query(ROOM_ID, null, 2);
        var second = service.query(ROOM_ID, first.nextCursor(), 2);

        assertThat(first.items()).hasSize(2).allSatisfy(item -> {
            assertThat(item.evidence().botId()).isNotNull();
            assertThat(item.evidence().participationId()).isNotNull();
        });
        assertThat(first.hasMore()).isTrue();
        assertThat(second.snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(request.get())
                .extracting(
                        AnonymousLeaderboardQuery::roomId,
                        AnonymousLeaderboardQuery::viewerAccountId,
                        AnonymousLeaderboardQuery::snapshotId,
                        AnonymousLeaderboardQuery::afterRank,
                        AnonymousLeaderboardQuery::limit)
                .containsExactly(ROOM_ID, ACCOUNT_ID, SNAPSHOT_ID, 2, 3);
    }

    @Test
    void rejectsAnonymousAccessAndAResultWithoutOwnedEvidence() {
        var anonymous = new OwnedBotComparisonQueryService(
                query -> LeaderboardQueryResult.empty(), () -> null);
        assertThatThrownBy(() -> anonymous.query(ROOM_ID, null, 20))
                .isInstanceOf(LeaderboardAuthenticationException.class);

        var unsafe = new OwnedBotComparisonQueryService(
                query -> new LeaderboardQueryResult(
                        SNAPSHOT_ID,
                        "PUBLISHED",
                        Instant.parse("2026-08-02T00:00:00Z"),
                        List.of(new LeaderboardQueryRow(anchor(1), new AnonymousLeaderboardItem(
                                1, false, "other", BigDecimal.ONE, "ELIGIBLE",
                                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, null, null)))),
                () -> ACCOUNT_ID);
        assertThatThrownBy(() -> unsafe.query(ROOM_ID, null, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("owned evidence");
    }

    private static LeaderboardQueryRow row(int rank, int suffix) {
        UUID botId = id(100 + suffix);
        UUID participationId = id(200 + suffix);
        return new LeaderboardQueryRow(
                anchor(suffix),
                new AnonymousLeaderboardItem(
                        rank,
                        false,
                        "mine-" + rank,
                        BigDecimal.valueOf(100 - rank),
                        "ELIGIBLE",
                        BigDecimal.valueOf(100_000),
                        BigDecimal.valueOf(rank),
                        BigDecimal.ONE,
                        BigDecimal.TEN,
                        new OwnedLeaderboardEvidence(botId, participationId, id(300 + suffix), null, null)));
    }

    private static String anchor(int suffix) {
        return "sha256:" + String.format("%064x", suffix);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("96000000-0000-4000-8000-" + String.format("%012d", suffix));
    }
}

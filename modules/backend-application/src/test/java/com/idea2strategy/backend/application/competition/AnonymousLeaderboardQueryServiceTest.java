package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AnonymousLeaderboardQueryServiceTest {
    private static final UUID ROOM_ID = id(1);
    private static final UUID SNAPSHOT_ID = id(2);
    private static final UUID ACCOUNT_ID = id(3);

    @Test
    void keepsTheSnapshotAndStableLastRowKeyInTheNextCursor() {
        var request = new AtomicReference<AnonymousLeaderboardQuery>();
        LeaderboardQueryPort port = query -> {
            request.set(query);
            return new LeaderboardQueryResult(
                    SNAPSHOT_ID, "PUBLISHED", Instant.parse("2026-08-02T00:00:00Z"),
                    List.of(row(1, id(11)), row(2, id(12)), row(3, id(13))));
        };
        var service = new AnonymousLeaderboardQueryService(port, () -> ACCOUNT_ID);

        var first = service.query(ROOM_ID, null, 2);
        var second = service.query(ROOM_ID, first.nextCursor(), 2);

        assertThat(first.items()).hasSize(2);
        assertThat(first.hasMore()).isTrue();
        assertThat(new String(Base64.getUrlDecoder().decode(first.nextCursor()), StandardCharsets.UTF_8))
                .doesNotContain(id(12).toString())
                .contains(anchor(12));
        assertThat(second.snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(request.get())
                .extracting(
                        AnonymousLeaderboardQuery::roomId,
                        AnonymousLeaderboardQuery::viewerAccountId,
                        AnonymousLeaderboardQuery::snapshotId,
                        AnonymousLeaderboardQuery::afterRank,
                        AnonymousLeaderboardQuery::afterAnchor,
                        AnonymousLeaderboardQuery::limit)
                .containsExactly(ROOM_ID, ACCOUNT_ID, SNAPSHOT_ID, 2, anchor(12), 3);
    }

    @Test
    void returnsAnEmptyPageAndRejectsMalformedBoundaries() {
        var service = new AnonymousLeaderboardQueryService(
                query -> LeaderboardQueryResult.empty(), () -> ACCOUNT_ID);

        assertThat(service.query(ROOM_ID, null, 20))
                .isEqualTo(AnonymousLeaderboardPage.empty());
        assertThatThrownBy(() -> service.query(ROOM_ID, "not-a-cursor", 20))
                .isInstanceOf(InvalidLeaderboardCursorException.class)
                .hasMessageContaining("cursor");
        assertThatThrownBy(() -> service.query(ROOM_ID, null, 51))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        var anonymous = new AnonymousLeaderboardQueryService(
                query -> LeaderboardQueryResult.empty(), () -> null);
        assertThatThrownBy(() -> anonymous.query(ROOM_ID, null, 20))
                .isInstanceOf(LeaderboardAuthenticationException.class);
    }

    @Test
    void normalizesEveryMalformedCursorShapeToTheCursorException() {
        var service = new AnonymousLeaderboardQueryService(
                query -> LeaderboardQueryResult.empty(), () -> ACCOUNT_ID);
        String hash = anchor(1);
        for (String cursor : List.of(
                "%%%",
                encoded("bad-parts"),
                encoded(SNAPSHOT_ID + "|0|" + hash),
                encoded(SNAPSHOT_ID + "|1|not-a-hash"),
                encoded("not-a-uuid|1|" + hash))) {
            assertThatThrownBy(() -> service.query(ROOM_ID, cursor, 20))
                    .isInstanceOf(InvalidLeaderboardCursorException.class)
                    .hasMessage("cursor is invalid");
        }
    }

    private static LeaderboardQueryRow row(int rank, UUID ignoredParticipationId) {
        return new LeaderboardQueryRow(
                anchor(rank == 1 ? 11 : rank == 2 ? 12 : 13),
                new AnonymousLeaderboardItem(
                        rank, false, "bot-" + rank, BigDecimal.valueOf(100 - rank), "ELIGIBLE",
                        BigDecimal.valueOf(100_000), BigDecimal.valueOf(rank), BigDecimal.ONE,
                        BigDecimal.TEN, null));
    }

    private static String anchor(int suffix) {
        return "sha256:" + String.format("%064x", suffix);
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static UUID id(int suffix) {
        return UUID.fromString("91000000-0000-4000-8000-" + String.format("%012d", suffix));
    }
}

package com.idea2strategy.backend.application.competition;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class RoomLeaderboardQueryService {
    private static final String CURSOR_VERSION = "room-leaderboard.v1";

    private final RoomLeaderboardQueryPort queryPort;
    private final Supplier<UUID> viewerAccountId;

    public RoomLeaderboardQueryService(
            RoomLeaderboardQueryPort queryPort, Supplier<UUID> viewerAccountId) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.viewerAccountId = Objects.requireNonNull(viewerAccountId, "viewerAccountId");
    }

    public RoomLeaderboardPage query(
            UUID roomId,
            String leaderboardCursor,
            int leaderboardLimit,
            String myBotsCursor,
            int myBotsLimit) {
        Objects.requireNonNull(roomId, "roomId");
        validateLimit(leaderboardLimit);
        validateLimit(myBotsLimit);
        UUID viewerId = viewerAccountId.get();
        if (viewerId == null) {
            throw new LeaderboardAuthenticationException("Authentication is required to view room results");
        }
        Cursor leaderboard = decode(leaderboardCursor, CursorKind.LEADERBOARD, roomId, viewerId);
        Cursor myBots = decode(myBotsCursor, CursorKind.MY_BOTS, roomId, viewerId);
        UUID snapshotId = pinnedSnapshot(leaderboard, myBots);

        var result = queryPort.queryRoomLeaderboard(new RoomLeaderboardQuery(
                roomId,
                viewerId,
                snapshotId,
                leaderboard == null ? null : leaderboard.rank(),
                leaderboard == null ? null : leaderboard.anchor(),
                leaderboardLimit + 1,
                myBots == null ? null : myBots.rank(),
                myBots == null ? null : myBots.anchor(),
                myBotsLimit + 1));
        if (result.room() == null || result.leaderboard().snapshotId() == null) {
            return RoomLeaderboardPage.empty();
        }
        if (!result.leaderboard().snapshotId().equals(result.ownedBots().snapshotId())) {
            throw new IllegalStateException("Integrated room results must share one snapshot");
        }
        if (snapshotId != null && !snapshotId.equals(result.leaderboard().snapshotId())) {
            throw new InvalidLeaderboardCursorException("cursor snapshot is invalid for this room");
        }

        return new RoomLeaderboardPage(
                result.room(),
                result.viewerParticipations(),
                leaderboardPage(result.leaderboard(), leaderboardLimit, roomId, viewerId),
                ownedPage(result.ownedBots(), myBotsLimit, roomId, viewerId));
    }

    private static AnonymousLeaderboardPage leaderboardPage(
            LeaderboardQueryResult result, int limit, UUID roomId, UUID viewerId) {
        boolean hasMore = result.rows().size() > limit;
        List<LeaderboardQueryRow> selected = hasMore ? result.rows().subList(0, limit) : result.rows();
        String next = hasMore
                ? encode(CursorKind.LEADERBOARD, roomId, viewerId, result.snapshotId(), selected.getLast())
                : null;
        var items = selected.stream().map(row -> {
            var item = row.item();
            return new AnonymousLeaderboardItem(
                    item.rank(), item.jointRank(), item.anonymousAlias(), item.score(),
                    item.eligibilityStatus(), item.equityAmount(), item.totalReturnPct(),
                    item.maxDrawdownPct(), item.sharpeRatio(), null);
        }).toList();
        return new AnonymousLeaderboardPage(
                result.snapshotId(), result.snapshotStatus(), result.cutoffAt(), items, next, hasMore);
    }

    private static OwnedBotComparisonPage ownedPage(
            LeaderboardQueryResult result, int limit, UUID roomId, UUID viewerId) {
        boolean hasMore = result.rows().size() > limit;
        List<LeaderboardQueryRow> selected = hasMore ? result.rows().subList(0, limit) : result.rows();
        String next = hasMore
                ? encode(CursorKind.MY_BOTS, roomId, viewerId, result.snapshotId(), selected.getLast())
                : null;
        var items = selected.stream().map(row -> {
            var item = row.item();
            if (item.viewerEvidence() == null) {
                throw new IllegalStateException("Owned bot comparison row is missing owned evidence");
            }
            return new OwnedBotComparisonItem(
                    item.rank(), item.jointRank(), item.anonymousAlias(), item.score(),
                    item.eligibilityStatus(), item.equityAmount(), item.totalReturnPct(),
                    item.maxDrawdownPct(), item.sharpeRatio(), item.viewerEvidence());
        }).toList();
        return new OwnedBotComparisonPage(
                result.snapshotId(), result.snapshotStatus(), result.cutoffAt(), items, next, hasMore);
    }

    private static UUID pinnedSnapshot(Cursor leaderboard, Cursor myBots) {
        if (leaderboard != null && myBots != null
                && !leaderboard.snapshotId().equals(myBots.snapshotId())) {
            throw new InvalidLeaderboardCursorException("cursor snapshots do not match");
        }
        return leaderboard != null
                ? leaderboard.snapshotId()
                : myBots == null ? null : myBots.snapshotId();
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("limit must be between 1 and 50");
        }
    }

    private static String encode(
            CursorKind kind,
            UUID roomId,
            UUID viewerId,
            UUID snapshotId,
            LeaderboardQueryRow row) {
        int rank = row.item().rank() == null ? Integer.MAX_VALUE : row.item().rank();
        String value = String.join(
                "|", CURSOR_VERSION, kind.name(), roomId.toString(), viewerId.toString(),
                snapshotId.toString(), Integer.toString(rank), row.cursorAnchor());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(
            String encoded, CursorKind expectedKind, UUID expectedRoomId, UUID expectedViewerId) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length != 7
                    || !CURSOR_VERSION.equals(parts[0])
                    || !expectedKind.name().equals(parts[1])
                    || !expectedRoomId.equals(UUID.fromString(parts[2]))
                    || !expectedViewerId.equals(UUID.fromString(parts[3]))) {
                throw new IllegalArgumentException();
            }
            int rank = Integer.parseInt(parts[5]);
            if (rank < 1 || !parts[6].matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException();
            }
            return new Cursor(UUID.fromString(parts[4]), rank, parts[6]);
        } catch (RuntimeException exception) {
            throw new InvalidLeaderboardCursorException("cursor is invalid");
        }
    }

    private enum CursorKind { LEADERBOARD, MY_BOTS }

    private record Cursor(UUID snapshotId, int rank, String anchor) {}
}

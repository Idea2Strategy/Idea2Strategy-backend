package com.idea2strategy.backend.application.competition;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class AnonymousLeaderboardQueryService {
    private final LeaderboardQueryPort queryPort;
    private final Supplier<UUID> viewerAccountId;

    public AnonymousLeaderboardQueryService(LeaderboardQueryPort queryPort, Supplier<UUID> viewerAccountId) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.viewerAccountId = Objects.requireNonNull(viewerAccountId, "viewerAccountId");
    }

    public AnonymousLeaderboardPage query(UUID roomId, String cursor, int limit) {
        Objects.requireNonNull(roomId, "roomId");
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("limit must be between 1 and 50");
        }
        UUID viewerId = viewerAccountId.get();
        if (viewerId == null) {
            throw new LeaderboardAuthenticationException("Authentication is required to view a leaderboard");
        }
        Cursor decoded = decode(cursor);
        var result = queryPort.query(new AnonymousLeaderboardQuery(
                roomId,
                viewerId,
                decoded == null ? null : decoded.snapshotId(),
                decoded == null ? null : decoded.rank(),
                decoded == null ? null : decoded.anchor(),
                limit + 1));
        if (result.snapshotId() == null) {
            return AnonymousLeaderboardPage.empty();
        }
        boolean hasMore = result.rows().size() > limit;
        var selected = hasMore ? result.rows().subList(0, limit) : result.rows();
        String nextCursor = hasMore ? encode(result.snapshotId(), selected.getLast()) : null;
        return new AnonymousLeaderboardPage(
                result.snapshotId(), result.snapshotStatus(), result.cutoffAt(),
                selected.stream().map(LeaderboardQueryRow::item).toList(), nextCursor, hasMore);
    }

    private static String encode(UUID snapshotId, LeaderboardQueryRow row) {
        int cursorRank = row.item().rank() == null ? Integer.MAX_VALUE : row.item().rank();
        String value = snapshotId + "|" + cursorRank + "|" + row.cursorAnchor();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException();
            }
            int rank = Integer.parseInt(parts[1]);
            if (rank < 1) {
                throw new IllegalArgumentException();
            }
            if (!parts[2].matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException();
            }
            return new Cursor(UUID.fromString(parts[0]), rank, parts[2]);
        } catch (RuntimeException exception) {
            throw new InvalidLeaderboardCursorException("cursor is invalid");
        }
    }

    private record Cursor(UUID snapshotId, int rank, String anchor) {}
}

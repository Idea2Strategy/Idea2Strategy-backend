package com.idea2strategy.backend.application.competition;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

public final class PublicRoomDiscoveryService {
    private final PublicRoomSearchPort searchPort;

    public PublicRoomDiscoveryService(PublicRoomSearchPort searchPort) {
        this.searchPort = Objects.requireNonNull(searchPort, "searchPort");
    }

    public PublicRoomPage search(String query, String cursor, int limit) {
        if (limit < 1 || limit > 50) {
            throw new IllegalArgumentException("limit must be between 1 and 50");
        }
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.length() > 120) {
            throw new IllegalArgumentException("query must contain at most 120 characters");
        }
        Cursor decoded = decode(cursor);
        var rows = searchPort.search(
                normalizedQuery,
                decoded == null ? null : decoded.createdAt(),
                decoded == null ? null : decoded.id(),
                limit + 1);
        boolean hasMore = rows.size() > limit;
        var items = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore ? encode(items.getLast()) : null;
        return new PublicRoomPage(items, nextCursor, hasMore);
    }

    private static String encode(PublicRoomItem item) {
        String value = item.createdAt() + "|" + item.id();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = value.indexOf('|');
            if (separator < 1 || separator == value.length() - 1) {
                throw new IllegalArgumentException();
            }
            return new Cursor(
                    Instant.parse(value.substring(0, separator)),
                    UUID.fromString(value.substring(separator + 1)));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("cursor is invalid", exception);
        }
    }

    private record Cursor(Instant createdAt, UUID id) {}
}

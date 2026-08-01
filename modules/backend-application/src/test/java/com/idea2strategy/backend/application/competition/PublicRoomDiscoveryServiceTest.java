package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.domain.competition.RoomOrganizerType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicRoomDiscoveryServiceTest {
    private static final Instant CREATED_AT = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void returnsAStableCursorWithoutExposingAnExtraRow() {
        var first = item("71000000-0000-4000-8000-000000000001", "Alpha", CREATED_AT);
        var second = item("71000000-0000-4000-8000-000000000002", "Beta", CREATED_AT.minusSeconds(1));
        var service = new PublicRoomDiscoveryService((query, beforeCreatedAt, beforeId, limit) ->
                List.of(first, second));

        var page = service.search("a", null, 1);

        assertThat(page.items()).containsExactly(first);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
    }

    @Test
    void rejectsInvalidLimitsAndCursorsBeforeQuerying() {
        var service = new PublicRoomDiscoveryService((query, beforeCreatedAt, beforeId, limit) -> List.of());

        assertThatThrownBy(() -> service.search(null, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> service.search(null, "not-a-cursor", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor");
    }

    private static PublicRoomItem item(String id, String name, Instant createdAt) {
        return new PublicRoomItem(
                UUID.fromString(id),
                name,
                RoomOrganizerType.USER,
                createdAt,
                createdAt.plusSeconds(60),
                createdAt.plusSeconds(3600),
                10,
                1);
    }
}

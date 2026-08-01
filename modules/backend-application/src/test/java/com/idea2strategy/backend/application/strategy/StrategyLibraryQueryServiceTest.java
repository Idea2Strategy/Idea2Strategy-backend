package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.domain.strategy.StrategyMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StrategyLibraryQueryServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");
    private static final UUID DRAFT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID RELEASED_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID PACKAGE_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");

    @Test
    void scopesQueriesToCurrentOwnerAndContinuesFromOpaqueCursor() {
        var observedOwner = new AtomicReference<UUID>();
        var observedSnapshot = new AtomicReference<Instant>();
        var observedAfter = new AtomicReference<StrategyLibraryPosition>();
        StrategyLibraryQueryPort port = (ownerId, snapshotAt, after, limit) -> {
            observedOwner.set(ownerId);
            observedSnapshot.set(snapshotAt);
            observedAfter.set(after);
            if (after != null) {
                return List.of(item(PACKAGE_ID, StrategyLibraryItemKind.PACKAGE, StrategyMode.BASIC,
                        NOW.minusSeconds(30)));
            }
            return List.of(
                    item(DRAFT_ID, StrategyLibraryItemKind.DRAFT, StrategyMode.BASIC, NOW.minusSeconds(10)),
                    item(RELEASED_ID, StrategyLibraryItemKind.RELEASED, StrategyMode.PRO, NOW.minusSeconds(20)),
                    item(PACKAGE_ID, StrategyLibraryItemKind.PACKAGE, StrategyMode.BASIC, NOW.minusSeconds(30)));
        };
        var service = new StrategyLibraryQueryService(
                port, () -> OWNER_ID, Clock.fixed(NOW, ZoneOffset.UTC));

        StrategyLibraryPage first = service.list(null, 2);

        assertThat(observedOwner).hasValue(OWNER_ID);
        assertThat(observedSnapshot).hasValue(NOW);
        assertThat(first.items()).extracting(StrategyLibraryItem::id)
                .containsExactly(DRAFT_ID, RELEASED_ID);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(first.items()).isUnmodifiable();

        StrategyLibraryPage second = service.list(first.nextCursor(), 2);

        assertThat(observedSnapshot).hasValue(NOW);
        assertThat(observedAfter.get()).isEqualTo(new StrategyLibraryPosition(
                NOW.minusSeconds(20), StrategyLibraryItemKind.RELEASED, RELEASED_ID));
        assertThat(second.items()).extracting(StrategyLibraryItem::id).containsExactly(PACKAGE_ID);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void rejectsInvalidLimitsAndMalformedCursorsBeforeQueryingStorage() {
        StrategyLibraryQueryPort port = (ownerId, snapshotAt, after, limit) -> {
            throw new AssertionError("storage must not be called");
        };
        var service = new StrategyLibraryQueryService(
                port, () -> OWNER_ID, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.list(null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and 100");
        assertThatThrownBy(() -> service.list(null, 101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("not-a-valid-cursor", 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cursor is invalid");
        String futureCursor = StrategyLibraryCursor.encode(
                NOW.plusSeconds(1),
                new StrategyLibraryPosition(NOW, StrategyLibraryItemKind.DRAFT, DRAFT_ID));
        assertThatThrownBy(() -> service.list(futureCursor, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cursor is invalid");
    }

    private static StrategyLibraryItem item(
            UUID id, StrategyLibraryItemKind kind, StrategyMode mode, Instant updatedAt) {
        return new StrategyLibraryItem(
                id,
                kind,
                mode,
                kind.name(),
                null,
                kind == StrategyLibraryItemKind.DRAFT ? "DRAFT" : "ACTIVE",
                kind == StrategyLibraryItemKind.DRAFT ? "PASSED" : null,
                kind == StrategyLibraryItemKind.RELEASED ? "SUCCEEDED" : null,
                kind == StrategyLibraryItemKind.DRAFT,
                updatedAt,
                null);
    }
}

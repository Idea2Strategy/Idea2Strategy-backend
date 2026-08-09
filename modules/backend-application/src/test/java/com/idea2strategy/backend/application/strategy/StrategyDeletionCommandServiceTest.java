package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class StrategyDeletionCommandServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");

    @Test
    void deletesTheOwnedStrategyAtTheCurrentTime() {
        StrategyDeletionCommandPort port = Mockito.mock(StrategyDeletionCommandPort.class);
        when(port.deleteOwned(STRATEGY_ID, OWNER_ID, NOW)).thenReturn(StrategyDeletionResult.DELETED);
        var service = new StrategyDeletionCommandService(
                port, () -> OWNER_ID, Clock.fixed(NOW, ZoneOffset.UTC));

        service.delete(STRATEGY_ID);

        verify(port).deleteOwned(STRATEGY_ID, OWNER_ID, NOW);
    }

    @Test
    void hidesAnUnknownOrUnownedStrategyBehindNotFound() {
        StrategyDeletionCommandPort port = Mockito.mock(StrategyDeletionCommandPort.class);
        when(port.deleteOwned(STRATEGY_ID, OWNER_ID, NOW)).thenReturn(StrategyDeletionResult.NOT_FOUND);
        var service = new StrategyDeletionCommandService(
                port, () -> OWNER_ID, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.delete(STRATEGY_ID))
                .isInstanceOf(NoSuchElementException.class);
    }
}

package com.idea2strategy.backend.application.botoperations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class BotDeletionCommandServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");

    @Test
    void deletesAnOwnedStoppedBotAtTheCurrentTime() {
        BotDeletionCommandPort port = Mockito.mock(BotDeletionCommandPort.class);
        when(port.deleteOwnedStopped(BOT_ID, OWNER_ID, NOW)).thenReturn(BotDeletionResult.DELETED);
        var service = new BotDeletionCommandService(port, () -> OWNER_ID, Clock.fixed(NOW, ZoneOffset.UTC));

        service.delete(BOT_ID);

        verify(port).deleteOwnedStopped(BOT_ID, OWNER_ID, NOW);
    }

    @Test
    void rejectsDeletionUntilTheBotIsStopped() {
        BotDeletionCommandPort port = Mockito.mock(BotDeletionCommandPort.class);
        when(port.deleteOwnedStopped(BOT_ID, OWNER_ID, NOW)).thenReturn(BotDeletionResult.NOT_STOPPED);
        var service = new BotDeletionCommandService(port, () -> OWNER_ID, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.delete(BOT_ID))
                .isInstanceOf(BotDeletionConflictException.class);
    }

    @Test
    void hidesAnUnknownOrUnownedBotBehindNotFound() {
        BotDeletionCommandPort port = Mockito.mock(BotDeletionCommandPort.class);
        when(port.deleteOwnedStopped(BOT_ID, OWNER_ID, NOW)).thenReturn(BotDeletionResult.NOT_FOUND);
        var service = new BotDeletionCommandService(port, () -> OWNER_ID, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.delete(BOT_ID))
                .isInstanceOf(BotOperationsNotFoundException.class);
    }
}

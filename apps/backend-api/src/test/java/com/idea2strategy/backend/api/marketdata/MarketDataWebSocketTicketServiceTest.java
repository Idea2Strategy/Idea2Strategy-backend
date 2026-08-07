package com.idea2strategy.backend.api.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarketDataWebSocketTicketServiceTest {
    @Test
    void ticketIsAuthenticatedShortLivedAndOneUse() {
        UUID accountId = UUID.fromString("7a06416a-facf-4e60-a453-9350f729ff06");
        var service = new MarketDataWebSocketTicketService(
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC));

        var ticket = service.issue(accountId);

        assertEquals(accountId, service.consume(ticket.ticket()).orElseThrow());
        assertTrue(service.consume(ticket.ticket()).isEmpty());
        assertEquals(Instant.parse("2026-08-08T00:00:30Z"), ticket.expiresAt());
    }
}

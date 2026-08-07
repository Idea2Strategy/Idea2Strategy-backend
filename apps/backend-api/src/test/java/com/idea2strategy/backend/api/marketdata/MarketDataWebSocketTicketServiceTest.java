package com.idea2strategy.backend.api.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.messaging.marketdata.RedisSingleUseTicketStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    @Test
    void aTicketIssuedByOneBackendReplicaCanBeConsumedByAnother() {
        var shared = new ConcurrentHashMap<String, String>();
        RedisSingleUseTicketStore store = mock(RedisSingleUseTicketStore.class);
        doAnswer(invocation -> {
                    shared.put(invocation.getArgument(0), invocation.getArgument(1));
                    return null;
                })
                .when(store)
                .put(anyString(), anyString(), any());
        when(store.take(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(shared.remove(invocation.getArgument(0))));
        Clock clock = Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC);
        var issuer = new MarketDataWebSocketTicketService(clock, store);
        var consumer = new MarketDataWebSocketTicketService(clock, store);
        UUID accountId = UUID.fromString("7a06416a-facf-4e60-a453-9350f729ff06");

        var ticket = issuer.issue(accountId);

        assertEquals(accountId, consumer.consume(ticket.ticket()).orElseThrow());
        assertTrue(issuer.consume(ticket.ticket()).isEmpty());
    }
}

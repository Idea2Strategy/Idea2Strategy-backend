package com.idea2strategy.backend.api.marketdata;

import com.idea2strategy.backend.messaging.marketdata.RedisSingleUseTicketStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class MarketDataWebSocketTicketService {
    private static final Duration LIFETIME = Duration.ofSeconds(30);

    private final Clock clock;
    private final TicketRepository tickets;

    MarketDataWebSocketTicketService(Clock clock) {
        this(clock, new InMemoryTicketRepository(clock));
    }

    MarketDataWebSocketTicketService(Clock clock, RedisSingleUseTicketStore store) {
        this(clock, new TicketRepository() {
            @Override
            public void put(String value, UUID accountId, Duration lifetime) {
                store.put(value, accountId.toString(), lifetime);
            }

            @Override
            public Optional<UUID> take(String value) {
                return store.take(value).map(UUID::fromString);
            }
        });
    }

    private MarketDataWebSocketTicketService(Clock clock, TicketRepository tickets) {
        this.clock = clock;
        this.tickets = tickets;
    }

    IssuedTicket issue(UUID accountId) {
        Instant expiresAt = clock.instant().plus(LIFETIME);
        String value = UUID.randomUUID().toString();
        tickets.put(value, accountId, LIFETIME);
        return new IssuedTicket(value, expiresAt);
    }

    Optional<UUID> consume(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return tickets.take(value);
    }

    record IssuedTicket(String ticket, Instant expiresAt) {}

    private interface TicketRepository {
        void put(String value, UUID accountId, Duration lifetime);

        Optional<UUID> take(String value);
    }

    private static final class InMemoryTicketRepository implements TicketRepository {
        private final Clock clock;
        private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

        private InMemoryTicketRepository(Clock clock) {
            this.clock = clock;
        }

        @Override
        public void put(String value, UUID accountId, Duration lifetime) {
            tickets.put(value, new Ticket(accountId, clock.instant().plus(lifetime)));
        }

        @Override
        public Optional<UUID> take(String value) {
            Ticket ticket = tickets.remove(value);
            if (ticket == null || !clock.instant().isBefore(ticket.expiresAt())) {
                return Optional.empty();
            }
            return Optional.of(ticket.accountId());
        }
    }

    private record Ticket(UUID accountId, Instant expiresAt) {}
}

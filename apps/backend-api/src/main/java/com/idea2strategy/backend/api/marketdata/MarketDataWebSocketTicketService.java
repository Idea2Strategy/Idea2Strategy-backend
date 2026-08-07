package com.idea2strategy.backend.api.marketdata;

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
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    MarketDataWebSocketTicketService(Clock clock) {
        this.clock = clock;
    }

    IssuedTicket issue(UUID accountId) {
        Instant expiresAt = clock.instant().plus(LIFETIME);
        String value = UUID.randomUUID().toString();
        tickets.put(value, new Ticket(accountId, expiresAt));
        return new IssuedTicket(value, expiresAt);
    }

    Optional<UUID> consume(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        Ticket ticket = tickets.remove(value);
        if (ticket == null || !clock.instant().isBefore(ticket.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(ticket.accountId());
    }

    record IssuedTicket(String ticket, Instant expiresAt) {}

    private record Ticket(UUID accountId, Instant expiresAt) {}
}

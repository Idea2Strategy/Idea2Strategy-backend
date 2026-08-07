package com.idea2strategy.backend.api.marketdata;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market-data/websocket-ticket")
@ConditionalOnProperty(name = {"spring.datasource.url", "market-data.redis-uri"})
public class MarketDataWebSocketTicketController {
    private final CurrentPrincipal principal;
    private final MarketDataWebSocketTicketService tickets;

    public MarketDataWebSocketTicketController(
            CurrentPrincipal principal, MarketDataWebSocketTicketService tickets) {
        this.principal = principal;
        this.tickets = tickets;
    }

    @PostMapping
    public TicketResponse issue() {
        var issued = tickets.issue(principal.accountId());
        return new TicketResponse(issued.ticket(), issued.expiresAt());
    }

    public record TicketResponse(String ticket, Instant expiresAt) {}
}

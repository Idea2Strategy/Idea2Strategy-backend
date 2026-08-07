package com.idea2strategy.backend.api.marketdata;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

final class MarketDataTicketHandshakeInterceptor implements HandshakeInterceptor {
    static final String ACCOUNT_ID_ATTRIBUTE = "marketDataAccountId";

    private final MarketDataWebSocketTicketService tickets;

    MarketDataTicketHandshakeInterceptor(MarketDataWebSocketTicketService tickets) {
        this.tickets = tickets;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        String ticket = UriComponentsBuilder.fromUri(request.getURI())
                .build().getQueryParams().getFirst("ticket");
        var accountId = tickets.consume(ticket);
        if (accountId.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(ACCOUNT_ID_ATTRIBUTE, accountId.get());
        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {}
}

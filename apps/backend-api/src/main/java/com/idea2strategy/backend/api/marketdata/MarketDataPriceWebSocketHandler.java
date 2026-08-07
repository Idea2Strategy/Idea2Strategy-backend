package com.idea2strategy.backend.api.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import com.idea2strategy.backend.messaging.marketdata.RedisDisplayPriceAdapter;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

final class MarketDataPriceWebSocketHandler extends TextWebSocketHandler {
    private final RedisDisplayPriceAdapter prices;
    private final BasicStrategyCatalogQueryService catalog;
    private final ObjectMapper mapper;
    private final Map<String, ClientState> clients = new ConcurrentHashMap<>();

    MarketDataPriceWebSocketHandler(
            RedisDisplayPriceAdapter prices,
            BasicStrategyCatalogQueryService catalog,
            ObjectMapper mapper) {
        this.prices = prices;
        this.catalog = catalog;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (!session.getAttributes().containsKey(MarketDataTicketHandshakeInterceptor.ACCOUNT_ID_ATTRIBUTE)) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("authenticated ticket required"));
            return;
        }
        clients.put(session.getId(), new ClientState(
                new ConcurrentWebSocketSessionDecorator(session, 5_000, 64 * 1024)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        ClientState state = clients.get(session.getId());
        if (state == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        JsonNode request = mapper.readTree(message.getPayload());
        String action = request.path("action").asText();
        UUID instrumentId = UUID.fromString(request.path("instrumentId").asText());
        switch (action) {
            case "subscribe" -> subscribe(state, session.getId(), instrumentId);
            case "unsubscribe" -> state.unsubscribe(instrumentId);
            default -> throw new IllegalArgumentException("action must be subscribe or unsubscribe");
        }
    }

    private void subscribe(ClientState state, String connectionId, UUID instrumentId) throws Exception {
        if (state.subscriptions.containsKey(instrumentId)) {
            return;
        }
        SupportedInstrument instrument = catalog.getSupportedInstruments().stream()
                .filter(candidate -> candidate.id().equals(instrumentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported market instrument"));
        AutoCloseable subscription = prices.subscribe(
                instrumentId,
                instrument.symbol(),
                connectionId,
                payload -> state.send(payload));
        state.subscriptions.put(instrumentId, subscription);
        state.send(mapper.writeValueAsString(Map.of(
                "type", "subscribed",
                "instrumentId", instrumentId.toString(),
                "symbol", instrument.symbol())));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ClientState state = clients.remove(session.getId());
        if (state != null) {
            state.close();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        session.close(CloseStatus.SERVER_ERROR);
    }

    private static final class ClientState {
        private final WebSocketSession session;
        private final Map<UUID, AutoCloseable> subscriptions = new ConcurrentHashMap<>();

        private ClientState(WebSocketSession session) {
            this.session = session;
        }

        private void send(String payload) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            } catch (IOException failure) {
                close();
            }
        }

        private void unsubscribe(UUID instrumentId) throws Exception {
            AutoCloseable subscription = subscriptions.remove(instrumentId);
            if (subscription != null) {
                subscription.close();
            }
        }

        private void close() {
            subscriptions.values().forEach(subscription -> {
                try {
                    subscription.close();
                } catch (Exception ignored) {
                    // Remaining subscriptions still need to be released.
                }
            });
            subscriptions.clear();
        }
    }
}

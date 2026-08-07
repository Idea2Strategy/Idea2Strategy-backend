package com.idea2strategy.backend.api.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import com.idea2strategy.backend.messaging.marketdata.RedisDisplayPriceAdapter;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.ByteBuffer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

final class MarketDataPriceWebSocketHandler extends TextWebSocketHandler implements AutoCloseable {
    private final RedisDisplayPriceAdapter prices;
    private final BasicStrategyCatalogQueryService catalog;
    private final ObjectMapper mapper;
    private final Map<String, ClientState> clients = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> connectionsByAccount = new ConcurrentHashMap<>();
    private final int maximumConnectionsPerAccount;
    private final int maximumSubscriptionsPerConnection;
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final Counter rejectedConnections;
    private final Counter deliveredUpdates;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "market-data-websocket-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    MarketDataPriceWebSocketHandler(
            RedisDisplayPriceAdapter prices,
            BasicStrategyCatalogQueryService catalog,
            ObjectMapper mapper,
            int maximumConnectionsPerAccount,
            int maximumSubscriptionsPerConnection,
            MeterRegistry meterRegistry) {
        this.prices = prices;
        this.catalog = catalog;
        this.mapper = mapper;
        if (maximumConnectionsPerAccount < 1 || maximumSubscriptionsPerConnection < 1) {
            throw new IllegalArgumentException("websocket connection and subscription limits must be positive");
        }
        this.maximumConnectionsPerAccount = maximumConnectionsPerAccount;
        this.maximumSubscriptionsPerConnection = maximumSubscriptionsPerConnection;
        this.rejectedConnections = Counter.builder("market_data.websocket.connections.rejected")
                .register(meterRegistry);
        this.deliveredUpdates = Counter.builder("market_data.websocket.updates.delivered")
                .register(meterRegistry);
        Gauge.builder("market_data.websocket.connections.active", activeConnections, AtomicInteger::get)
                .register(meterRegistry);
        heartbeat.scheduleAtFixedRate(this::heartbeatClients, 20, 20, TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (!session.getAttributes().containsKey(MarketDataTicketHandshakeInterceptor.ACCOUNT_ID_ATTRIBUTE)) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("authenticated ticket required"));
            return;
        }
        UUID accountId = (UUID) session.getAttributes()
                .get(MarketDataTicketHandshakeInterceptor.ACCOUNT_ID_ATTRIBUTE);
        Set<String> accountConnections = connectionsByAccount.computeIfAbsent(
                accountId, ignored -> new CopyOnWriteArraySet<>());
        synchronized (accountConnections) {
            if (accountConnections.size() >= maximumConnectionsPerAccount) {
                rejectedConnections.increment();
                if (accountConnections.isEmpty()) {
                    connectionsByAccount.remove(accountId, accountConnections);
                }
                session.close(new CloseStatus(1008, "connection limit exceeded"));
                return;
            }
            accountConnections.add(session.getId());
        }
        clients.put(session.getId(), new ClientState(
                accountId,
                new ConcurrentWebSocketSessionDecorator(session, 5_000, 64 * 1024)));
        activeConnections.incrementAndGet();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        ClientState state = clients.get(session.getId());
        if (state == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        state.markSeen();
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
        if (state.subscriptions.size() >= maximumSubscriptionsPerConnection) {
            throw new IllegalArgumentException("subscription limit exceeded");
        }
        SupportedInstrument instrument = catalog.getSupportedInstruments().stream()
                .filter(candidate -> candidate.id().equals(instrumentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported market instrument"));
        AutoCloseable subscription = prices.subscribe(
                instrumentId,
                instrument.symbol(),
                connectionId,
                payload -> {
                    state.send(payload);
                    deliveredUpdates.increment();
                });
        state.subscriptions.put(instrumentId, subscription);
        state.send(mapper.writeValueAsString(Map.of(
                "type", "subscribed",
                "instrumentId", instrumentId.toString(),
                "symbol", instrument.symbol())));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        removeClient(session.getId());
    }

    private void removeClient(String connectionId) {
        ClientState state = clients.remove(connectionId);
        if (state != null) {
            state.close();
            Set<String> accountConnections = connectionsByAccount.get(state.accountId);
            if (accountConnections != null) {
                accountConnections.remove(connectionId);
                if (accountConnections.isEmpty()) {
                    connectionsByAccount.remove(state.accountId, accountConnections);
                }
            }
            activeConnections.decrementAndGet();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        removeClient(session.getId());
        session.close(CloseStatus.SERVER_ERROR);
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        ClientState state = clients.get(session.getId());
        if (state != null) {
            state.markSeen();
        }
    }

    private void heartbeatClients() {
        long now = System.nanoTime();
        clients.values().forEach(state -> {
            try {
                if (now - state.lastSeenNanos > TimeUnit.SECONDS.toNanos(60)) {
                    state.session.close(new CloseStatus(1001, "heartbeat timeout"));
                } else if (state.session.isOpen()) {
                    state.session.sendMessage(new PingMessage(ByteBuffer.wrap(new byte[] {1})));
                }
            } catch (IOException failure) {
                state.closeTransport(CloseStatus.SERVER_ERROR);
            }
        });
    }

    @Override
    public void close() {
        heartbeat.shutdownNow();
        clients.values().stream()
                .toList()
                .forEach(state -> state.closeTransport(CloseStatus.GOING_AWAY));
        clients.clear();
        connectionsByAccount.clear();
        activeConnections.set(0);
    }

    private final class ClientState {
        private final UUID accountId;
        private final WebSocketSession session;
        private final Map<UUID, AutoCloseable> subscriptions = new ConcurrentHashMap<>();
        private volatile long lastSeenNanos = System.nanoTime();

        private ClientState(UUID accountId, WebSocketSession session) {
            this.accountId = accountId;
            this.session = session;
        }

        private void markSeen() {
            lastSeenNanos = System.nanoTime();
        }

        private void send(String payload) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            } catch (IOException failure) {
                closeTransport(CloseStatus.SERVER_ERROR);
            }
        }

        private void closeTransport(CloseStatus status) {
            removeClient(session.getId());
            try {
                session.close(status);
            } catch (IOException ignored) {
                // Local subscriptions and counters were already released by removeClient.
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

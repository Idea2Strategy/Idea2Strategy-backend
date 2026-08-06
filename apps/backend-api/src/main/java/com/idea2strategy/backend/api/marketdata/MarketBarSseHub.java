package com.idea2strategy.backend.api.marketdata;

import com.idea2strategy.backend.application.marketdata.MarketBarService;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public final class MarketBarSseHub implements AutoCloseable {
    private static final long STREAM_TIMEOUT_MILLIS = TimeUnit.HOURS.toMillis(1);
    private final MarketBarService service;
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "market-bar-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public MarketBarSseHub(MarketBarService service) {
        this.service = service;
    }

    public SseEmitter open(UUID instrumentId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<AutoCloseable> subscription = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();

        Runnable cleanup = () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            ScheduledFuture<?> scheduled = heartbeat.get();
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            closeQuietly(subscription.get());
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());

        try {
            subscription.set(service.subscribe(instrumentId, bar -> {
                try {
                    emitter.send(SseEmitter.event()
                            .id(bar.eventId())
                            .name("bar")
                            .data(bar));
                } catch (IOException | IllegalStateException exception) {
                    cleanup.run();
                    emitter.completeWithError(exception);
                }
            }));
            emitter.send(SseEmitter.event()
                    .name("ready")
                    .data(Map.of("instrumentId", instrumentId, "timeframe", "1m")));
            heartbeat.set(heartbeats.scheduleAtFixedRate(() -> {
                if (closed.get()) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event()
                            .name("heartbeat")
                            .data(Map.of("at", Instant.now())));
                } catch (IOException | IllegalStateException exception) {
                    cleanup.run();
                    emitter.completeWithError(exception);
                }
            }, 15, 15, TimeUnit.SECONDS));
        } catch (IOException | RuntimeException exception) {
            cleanup.run();
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Connection teardown is best effort after the HTTP stream closes.
        }
    }

    @Override
    public void close() {
        heartbeats.shutdownNow();
    }
}

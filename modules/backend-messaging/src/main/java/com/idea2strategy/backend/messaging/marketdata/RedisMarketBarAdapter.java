package com.idea2strategy.backend.messaging.marketdata;

import com.idea2strategy.backend.application.marketdata.MarketBar;
import com.idea2strategy.backend.application.marketdata.MarketBarPort;
import com.idea2strategy.backend.application.marketdata.MarketBarTimeframe;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public final class RedisMarketBarAdapter implements MarketBarPort, AutoCloseable {
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> commands;
    private final MarketBarJsonCodec codec = new MarketBarJsonCodec();
    private final String keyPrefix;

    public static RedisMarketBarAdapter connect(String redisUri, String keyPrefix) {
        if (redisUri == null || redisUri.isBlank()) {
            throw new IllegalArgumentException("market-data.redis-uri must not be blank");
        }
        RedisClient client = RedisClient.create(redisUri);
        try {
            return new RedisMarketBarAdapter(client, keyPrefix);
        } catch (RuntimeException exception) {
            client.shutdown();
            throw exception;
        }
    }

    RedisMarketBarAdapter(RedisClient client, String keyPrefix) {
        this.client = Objects.requireNonNull(client, "client");
        this.keyPrefix = requirePrefix(keyPrefix);
        this.commands = client.connect();
    }

    @Override
    public List<MarketBar> findRecent(UUID instrumentId, MarketBarTimeframe timeframe, int limit) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        if (limit < 1 || limit > 5000) {
            throw new IllegalArgumentException("limit must be between 1 and 5000");
        }
        if (timeframe.displayOnly()) {
            return findRecentDisplayBars(instrumentId, timeframe, limit);
        }
        List<String> encoded = commands.sync().zrevrange(
                recentBarsKey(instrumentId, timeframe), 0, limit - 1L);
        List<MarketBar> live = new ArrayList<>(encoded.size());
        encoded.forEach(value -> live.add(codec.decode(value, timeframe)));
        Collections.reverse(live);
        String historical = commands.sync().get(historyBarsKey(instrumentId, timeframe));
        List<MarketBar> history = historical == null
                ? List.of()
                : codec.decodeHistory(historical, instrumentId, timeframe);
        return mergeCanonicalHistoryWithLive(history, live, limit);
    }

    private List<MarketBar> findRecentDisplayBars(
            UUID instrumentId, MarketBarTimeframe timeframe, int limit) {
        int sourceLimit = Math.multiplyExact(limit, timeframe.minutes());
        List<String> buckets = commands.sync().zrevrange(
                displayMinuteBarIndexKey(instrumentId), 0, sourceLimit - 1L);
        if (buckets.isEmpty()) {
            return List.of();
        }
        var encoded = commands.sync().hmget(
                displayMinuteBarsKey(instrumentId), buckets.toArray(String[]::new));
        List<MarketBar> minuteBars = new ArrayList<>();
        encoded.forEach(value -> {
            if (value.hasValue()) {
                minuteBars.add(codec.decode(value.getValue(), MarketBarTimeframe.ONE_MINUTE));
            }
        });
        Collections.reverse(minuteBars);
        if (timeframe == MarketBarTimeframe.ONE_MINUTE) {
            return List.copyOf(minuteBars);
        }
        List<MarketBar> aggregated = aggregate(instrumentId, timeframe, minuteBars);
        return aggregated.size() <= limit
                ? aggregated
                : List.copyOf(aggregated.subList(aggregated.size() - limit, aggregated.size()));
    }

    static List<MarketBar> aggregate(
            UUID instrumentId, MarketBarTimeframe timeframe, List<MarketBar> source) {
        long bucketSeconds = timeframe.minutes() * 60L;
        Map<Long, MutableBar> grouped = new LinkedHashMap<>();
        for (MarketBar bar : source) {
            long bucket = Math.floorDiv(bar.occurredAt().getEpochSecond(), bucketSeconds) * bucketSeconds;
            grouped.computeIfAbsent(bucket, ignored -> new MutableBar(bar)).accept(bar);
        }
        List<MarketBar> result = new ArrayList<>();
        grouped.forEach((bucket, bar) -> result.add(bar.toMarketBar(instrumentId, timeframe, bucket)));
        return List.copyOf(result);
    }

    String displayMinuteBarsKey(UUID instrumentId) {
        return "{" + keyPrefix + ":market}:display:bars:1m:" + instrumentId;
    }

    String displayMinuteBarIndexKey(UUID instrumentId) {
        return "{" + keyPrefix + ":market}:display:bar-index:1m:" + instrumentId;
    }

    String recentBarsKey(UUID instrumentId, MarketBarTimeframe timeframe) {
        return "{" + keyPrefix + ":market}:bars:" + instrumentId + ":" + timeframe.value();
    }

    String historyBarsKey(UUID instrumentId, MarketBarTimeframe timeframe) {
        return "{" + keyPrefix + ":market}:history:bars:"
                + instrumentId + ":" + timeframe.value();
    }

    static List<MarketBar> mergeCanonicalHistoryWithLive(
            List<MarketBar> history, List<MarketBar> live, int limit) {
        TreeMap<Instant, MarketBar> merged = new TreeMap<>();
        history.forEach(bar -> merged.put(bar.occurredAt(), bar));
        Instant historicalCutoff = merged.isEmpty() ? null : merged.lastKey();
        live.stream()
                .filter(bar -> historicalCutoff == null || bar.occurredAt().isAfter(historicalCutoff))
                .forEach(bar -> merged.put(bar.occurredAt(), bar));
        List<MarketBar> ordered = new ArrayList<>(merged.values());
        return ordered.size() <= limit
                ? List.copyOf(ordered)
                : List.copyOf(ordered.subList(ordered.size() - limit, ordered.size()));
    }

    private static String requirePrefix(String value) {
        if (value == null || value.isBlank() || value.contains("{") || value.contains("}")) {
            throw new IllegalArgumentException("market-data.redis-key-prefix must be a plain non-empty value");
        }
        return value;
    }

    @Override
    public void close() {
        commands.close();
        client.shutdown();
    }

    private static final class MutableBar {
        private final String provider;
        private final String feed;
        private final BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private BigDecimal volume = BigDecimal.ZERO;

        private MutableBar(MarketBar first) {
            this.provider = first.provider();
            this.feed = first.feed();
            this.open = first.open();
            this.high = first.high();
            this.low = first.low();
            this.close = first.close();
        }

        private void accept(MarketBar bar) {
            high = high.max(bar.high());
            low = low.min(bar.low());
            close = bar.close();
            volume = volume.add(bar.volume());
        }

        private MarketBar toMarketBar(
                UUID instrumentId, MarketBarTimeframe timeframe, long bucket) {
            return new MarketBar(
                    "display:" + instrumentId + ":" + timeframe.value() + ":" + bucket,
                    instrumentId,
                    provider,
                    feed,
                    Instant.ofEpochSecond(bucket),
                    bucket,
                    0,
                    open,
                    high,
                    low,
                    close,
                    volume);
        }
    }
}

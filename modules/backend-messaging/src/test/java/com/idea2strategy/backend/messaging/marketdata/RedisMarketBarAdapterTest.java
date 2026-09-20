package com.idea2strategy.backend.messaging.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.marketdata.MarketBar;
import com.idea2strategy.backend.application.marketdata.MarketBarTimeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RedisMarketBarAdapterTest {
    private static final UUID INSTRUMENT = UUID.fromString("70000000-0000-4000-8000-000000000001");

    @Test
    void aggregatesPersistedDisplayMinutesIntoFiveMinuteOhlcv() {
        Instant start = Instant.parse("2026-08-06T14:30:00Z");
        List<MarketBar> minutes = IntStream.range(0, 5)
                .mapToObj(index -> minute(start.plusSeconds(index * 60L), index))
                .toList();

        List<MarketBar> result = RedisMarketBarAdapter.aggregate(
                INSTRUMENT, MarketBarTimeframe.FIVE_MINUTES, minutes);

        assertThat(result).singleElement().satisfies(bar -> {
            assertThat(bar.occurredAt()).isEqualTo(start);
            assertThat(bar.open()).isEqualByComparingTo("100");
            assertThat(bar.high()).isEqualByComparingTo("105");
            assertThat(bar.low()).isEqualByComparingTo("99");
            assertThat(bar.close()).isEqualByComparingTo("104");
            assertThat(bar.volume()).isEqualByComparingTo("50");
        });
    }

    @Test
    void mergesOnlyLiveBarsAfterTheCanonicalHistoryCutoff() {
        Instant start = Instant.parse("2026-08-06T14:30:00Z");
        MarketBar historical = minute(start, 0);
        MarketBar historicalLatest = minute(start.plusSeconds(1800), 1);
        MarketBar staleBeforeHistory = new MarketBar(
                "stale-before-history", INSTRUMENT, "ALPACA", "SIP",
                start, 1, 1,
                BigDecimal.valueOf(150), BigDecimal.valueOf(151),
                BigDecimal.valueOf(149), BigDecimal.valueOf(150), BigDecimal.TEN);
        MarketBar staleOverlap = new MarketBar(
                "stale-overlap", INSTRUMENT, "ALPACA", "SIP",
                start.plusSeconds(1800), 2, 1,
                BigDecimal.valueOf(200), BigDecimal.valueOf(201),
                BigDecimal.valueOf(199), BigDecimal.valueOf(200), BigDecimal.TEN);
        MarketBar liveLatest = minute(start.plusSeconds(3600), 3);

        List<MarketBar> result = RedisMarketBarAdapter.mergeCanonicalHistoryWithLive(
                List.of(historical, historicalLatest),
                List.of(staleBeforeHistory, staleOverlap, liveLatest),
                3);

        assertThat(result).extracting(MarketBar::eventId)
                .containsExactly("minute-0", "minute-1", "minute-3");
    }

    @Test
    void decodesCompactAdjustedHistoryProjection() {
        String payload = """
                {"schemaVersion":1,"adjustment":"all","timeframe":"30m",
                 "instrumentId":"70000000-0000-4000-8000-000000000001","bars":[
                  {"t":"2026-08-06T14:30:00Z","o":100,"h":102,"l":99,"c":101,"v":1200}]}
                """;

        List<MarketBar> result = new MarketBarJsonCodec().decodeHistory(
                payload, INSTRUMENT, MarketBarTimeframe.THIRTY_MINUTES);

        assertThat(result).singleElement().satisfies(bar -> {
            assertThat(bar.occurredAt()).isEqualTo("2026-08-06T14:30:00Z");
            assertThat(bar.close()).isEqualByComparingTo("101");
            assertThat(bar.provider()).isEqualTo("ALPACA");
        });
    }

    private static MarketBar minute(Instant at, int index) {
        BigDecimal open = BigDecimal.valueOf(100 + index);
        return new MarketBar(
                "minute-" + index,
                INSTRUMENT,
                "ALPACA",
                "SIP",
                at,
                at.getEpochSecond(),
                0,
                open,
                open.add(BigDecimal.ONE),
                open.subtract(BigDecimal.ONE),
                open,
                BigDecimal.TEN);
    }
}

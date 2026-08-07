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

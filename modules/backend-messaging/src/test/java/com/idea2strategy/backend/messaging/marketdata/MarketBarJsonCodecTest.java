package com.idea2strategy.backend.messaging.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarketBarJsonCodecTest {
    @Test
    void decodesTheFlatPayloadPublishedByTradingEngine() {
        var bar = new MarketBarJsonCodec().decode("""
                {"schemaVersion":1,"eventId":"event-1",
                 "instrumentId":"70000000-0000-4000-8000-000000000001",
                 "provider":"ALPACA","feed":"SIP","eventType":"BAR_1M",
                 "occurredAt":"2026-08-06T14:30:00Z","sequence":42,"revision":0,
                 "open":210.00,"high":210.20,"low":209.90,"close":210.12,"volume":2500}
                """, com.idea2strategy.backend.application.marketdata.MarketBarTimeframe.ONE_MINUTE);

        assertThat(bar.close()).isEqualByComparingTo("210.12");
        assertThat(bar.sequence()).isEqualTo(42);
    }

    @Test
    void preservesIndexHistoryProvenanceInsteadOfLabellingItAsSipEquityData() {
        UUID instrumentId = UUID.fromString("70000000-0000-4000-8000-000000000500");
        var bars = new MarketBarJsonCodec().decodeHistory("""
                {"actualFrom":"2026-07-29T20:00:00Z","actualTo":"2026-07-29T20:00:00Z","adjustment":"all","bars":[{"c":27192.31,"h":27200,"l":27000,"o":27100,"t":"2026-07-29T20:00:00Z","v":0}],"datasetHashes":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"],"feed":"NDX_DAILY","instrumentId":"70000000-0000-4000-8000-000000000500","manifestIds":["manifest-index"],"objectHashes":["bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"],"projectionHash":"5ce5a8ac5ce8f021ba8e6c1ac6bc50f4304828b6e3896bdeaa3b0ebdb69b09c6","provider":"NASDAQ_INDEX","revision":1,"rowCount":1,"schemaVersion":2,"timeframe":"1d"}
                """, instrumentId,
                com.idea2strategy.backend.application.marketdata.MarketBarTimeframe.ONE_DAY);

        assertThat(bars).singleElement().satisfies(bar -> {
            assertThat(bar.provider()).isEqualTo("NASDAQ_INDEX");
            assertThat(bar.feed()).isEqualTo("NDX_DAILY");
        });
    }
}

package com.idea2strategy.backend.messaging.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

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
}

package com.idea2strategy.backend.application.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarketBarServiceTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID AAPL_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");

    @Test
    void authorizesAndReturnsOnlySupportedInstrumentBars() {
        MarketBarPort port = mock(MarketBarPort.class);
        BasicStrategyCatalogQueryService catalog = mock(BasicStrategyCatalogQueryService.class);
        CurrentPrincipal principal = mock(CurrentPrincipal.class);
        when(principal.accountId()).thenReturn(ACCOUNT_ID);
        when(catalog.getSupportedInstruments()).thenReturn(List.of(
                new SupportedInstrument(AAPL_ID, "STOCK", "XNAS", "USD", "AAPL")));
        when(port.findRecent(AAPL_ID, MarketBarTimeframe.THIRTY_MINUTES, 300)).thenReturn(List.of(bar()));
        var service = new MarketBarService(port, catalog, principal);

        List<MarketBarView> result = service.findRecent(AAPL_ID, 300);

        assertThat(result).singleElement().satisfies(value -> {
            assertThat(value.symbol()).isEqualTo("AAPL");
            assertThat(value.close()).isEqualByComparingTo("210.12");
        });
        verify(principal).accountId();
    }

    @Test
    void rejectsUnknownInstrumentsBeforeReadingRedis() {
        MarketBarPort port = mock(MarketBarPort.class);
        BasicStrategyCatalogQueryService catalog = mock(BasicStrategyCatalogQueryService.class);
        when(catalog.getSupportedInstruments()).thenReturn(List.of());
        var service = new MarketBarService(port, catalog, () -> ACCOUNT_ID);

        assertThatThrownBy(() -> service.findRecent(AAPL_ID, 300))
                .isInstanceOf(UnsupportedMarketInstrumentException.class);
    }

    private static MarketBar bar() {
        return new MarketBar(
                "event-1", AAPL_ID, "ALPACA", "SIP",
                Instant.parse("2026-08-06T14:30:00Z"), 1, 0,
                new BigDecimal("210.00"), new BigDecimal("210.20"),
                new BigDecimal("209.90"), new BigDecimal("210.12"),
                new BigDecimal("2500"));
    }
}

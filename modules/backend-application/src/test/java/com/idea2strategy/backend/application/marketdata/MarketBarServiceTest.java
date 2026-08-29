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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarketBarServiceTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID AAPL_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID SPX_ID = UUID.fromString("70000000-0000-4000-8000-000000000500");

    @Test
    void exposesAndAuthorizesFixedIndexBenchmarksWithoutAddingThemToStrategyInstruments() {
        MarketBarPort port = mock(MarketBarPort.class);
        BasicStrategyCatalogQueryService catalog = mock(BasicStrategyCatalogQueryService.class);
        MarketBenchmarkCatalogPort benchmarks = () -> List.of(
                new SupportedInstrument(SPX_ID, "INDEX", "XNYS", "USD", "SPX"));
        when(catalog.getSupportedInstruments()).thenReturn(List.of());
        when(port.findRecent(SPX_ID, MarketBarTimeframe.ONE_DAY, 5000)).thenReturn(List.of(
                new MarketBar("spx-1", SPX_ID, "YAHOO_INDEX", "SPX_DAILY",
                        Instant.parse("2026-07-29T20:00:00Z"), 1, 0,
                        new BigDecimal("7300"), new BigDecimal("7330"),
                        new BigDecimal("7280"), new BigDecimal("7316.15"), BigDecimal.ZERO)));
        var service = new MarketBarService(port, catalog, benchmarks, () -> ACCOUNT_ID);

        assertThat(service.findBenchmarks()).extracting(SupportedInstrument::symbol)
                .containsExactly("SPX");
        assertThat(service.findRecentSnapshot(SPX_ID, MarketBarTimeframe.ONE_DAY, 5000).symbol())
                .isEqualTo("SPX");
    }

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

    @Test
    void allowsFiveThousandDailyBarsForLongRangeBenchmarkComparison() {
        MarketBarPort port = mock(MarketBarPort.class);
        BasicStrategyCatalogQueryService catalog = mock(BasicStrategyCatalogQueryService.class);
        when(catalog.getSupportedInstruments()).thenReturn(List.of(
                new SupportedInstrument(AAPL_ID, "STOCK", "XNAS", "USD", "AAPL")));
        when(port.findRecent(AAPL_ID, MarketBarTimeframe.ONE_DAY, 5000)).thenReturn(List.of(bar()));
        var service = new MarketBarService(port, catalog, () -> ACCOUNT_ID);

        MarketBarSnapshot result = service.findRecentSnapshot(
                AAPL_ID, MarketBarTimeframe.ONE_DAY, 5000);

        assertThat(result.bars()).hasSize(1);
        verify(port).findRecent(AAPL_ID, MarketBarTimeframe.ONE_DAY, 5000);
    }

    @Test
    void anchorsThreeMonthWindowToLatestStoredBarInsteadOfToday() {
        MarketBarPort port = mock(MarketBarPort.class);
        BasicStrategyCatalogQueryService catalog = mock(BasicStrategyCatalogQueryService.class);
        when(catalog.getSupportedInstruments()).thenReturn(List.of(
                new SupportedInstrument(AAPL_ID, "STOCK", "XNAS", "USD", "AAPL")));
        Instant requestedStart = Instant.parse("2026-04-30T20:00:00Z");
        Instant latest = Instant.parse("2026-07-30T20:00:00Z");
        when(port.findRecent(AAPL_ID, MarketBarTimeframe.THIRTY_MINUTES, 1000)).thenReturn(List.of(
                barAt(requestedStart.minus(30, ChronoUnit.MINUTES), "190"),
                barAt(requestedStart, "191"),
                barAt(latest, "210.12")));
        var service = new MarketBarService(port, catalog, () -> ACCOUNT_ID);

        MarketBarWindowSnapshot result = service.findWindowSnapshot(
                AAPL_ID, MarketBarTimeframe.THIRTY_MINUTES, MarketBarWindow.THREE_MONTHS);

        assertThat(result.requestedFrom()).isEqualTo(requestedStart);
        assertThat(result.requestedTo()).isEqualTo(latest);
        assertThat(result.availableFrom()).isEqualTo(requestedStart);
        assertThat(result.availableTo()).isEqualTo(latest);
        assertThat(result.coverageStatus()).isEqualTo(MarketBarCoverageStatus.COMPLETE);
        assertThat(result.reasonCode()).isNull();
        assertThat(result.bars()).extracting(MarketBarView::occurredAt)
                .containsExactly(requestedStart, latest);
    }

    @Test
    void reportsPartialAndEmptyWindowsWithLiteralCoverageReasons() {
        MarketBarPort port = mock(MarketBarPort.class);
        BasicStrategyCatalogQueryService catalog = mock(BasicStrategyCatalogQueryService.class);
        when(catalog.getSupportedInstruments()).thenReturn(List.of(
                new SupportedInstrument(AAPL_ID, "STOCK", "XNAS", "USD", "AAPL")));
        Instant latest = Instant.parse("2026-07-30T20:00:00Z");
        when(port.findRecent(AAPL_ID, MarketBarTimeframe.ONE_HOUR, 1000)).thenReturn(List.of(
                barAt(Instant.parse("2026-07-15T14:30:00Z"), "205"),
                barAt(latest, "210")));
        when(port.findRecent(AAPL_ID, MarketBarTimeframe.FOUR_HOURS, 1000)).thenReturn(List.of());
        var service = new MarketBarService(port, catalog, () -> ACCOUNT_ID);

        MarketBarWindowSnapshot partial = service.findWindowSnapshot(
                AAPL_ID, MarketBarTimeframe.ONE_HOUR, MarketBarWindow.ONE_MONTH);
        MarketBarWindowSnapshot empty = service.findWindowSnapshot(
                AAPL_ID, MarketBarTimeframe.FOUR_HOURS, MarketBarWindow.ONE_MONTH);

        assertThat(partial.requestedFrom()).isEqualTo("2026-06-30T20:00:00Z");
        assertThat(partial.coverageStatus()).isEqualTo(MarketBarCoverageStatus.PARTIAL);
        assertThat(partial.reasonCode()).isEqualTo("HISTORY_STARTS_AFTER_REQUESTED_WINDOW");
        assertThat(empty.coverageStatus()).isEqualTo(MarketBarCoverageStatus.EMPTY);
        assertThat(empty.reasonCode()).isEqualTo("NO_DATA_FOR_INSTRUMENT_TIMEFRAME");
        assertThat(empty.requestedFrom()).isNull();
        assertThat(empty.bars()).isEmpty();
    }

    private static MarketBar bar() {
        return new MarketBar(
                "event-1", AAPL_ID, "ALPACA", "SIP",
                Instant.parse("2026-08-06T14:30:00Z"), 1, 0,
                new BigDecimal("210.00"), new BigDecimal("210.20"),
                new BigDecimal("209.90"), new BigDecimal("210.12"),
                new BigDecimal("2500"));
    }

    private static MarketBar barAt(Instant at, String close) {
        BigDecimal value = new BigDecimal(close);
        return new MarketBar(
                "event-" + at, AAPL_ID, "ALPACA", "SIP", at, at.getEpochSecond(), 0,
                value, value.add(BigDecimal.ONE), value.subtract(BigDecimal.ONE), value,
                new BigDecimal("2500"));
    }
}

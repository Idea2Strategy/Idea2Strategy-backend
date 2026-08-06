package com.idea2strategy.backend.api.marketdata;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.marketdata.MarketBarPort;
import com.idea2strategy.backend.application.marketdata.MarketBarService;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MarketBarControllerTest {
    private static final UUID AAPL_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        MarketBarPort port = new MarketBarPort() {
            @Override
            public List<com.idea2strategy.backend.application.marketdata.MarketBar> findRecent(
                    UUID instrumentId, int limit) {
                return List.of(new com.idea2strategy.backend.application.marketdata.MarketBar(
                        "event-1", AAPL_ID, "ALPACA", "SIP",
                        Instant.parse("2026-08-06T14:30:00Z"), 1, 0,
                        new BigDecimal("210.00"), new BigDecimal("210.20"),
                        new BigDecimal("209.90"), new BigDecimal("210.12"),
                        new BigDecimal("2500")));
            }

            @Override
            public AutoCloseable subscribe(UUID instrumentId, java.util.function.Consumer<com.idea2strategy.backend.application.marketdata.MarketBar> listener) {
                return () -> {};
            }
        };
        BasicStrategyCatalogQueryService catalog = org.mockito.Mockito.mock(BasicStrategyCatalogQueryService.class);
        org.mockito.Mockito.when(catalog.getSupportedInstruments()).thenReturn(List.of(
                new SupportedInstrument(AAPL_ID, "STOCK", "XNAS", "USD", "AAPL")));
        var service = new MarketBarService(port, catalog, () -> UUID.randomUUID());
        mvc = MockMvcBuilders.standaloneSetup(new MarketBarController(service, new MarketBarSseHub(service)))
                .setControllerAdvice(new MarketBarExceptionHandler())
                .build();
    }

    @Test
    void returnsChronologicalOneMinuteBars() throws Exception {
        mvc.perform(get("/api/v1/market-data/instruments/{instrumentId}/bars", AAPL_ID)
                        .queryParam("limit", "300"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instrumentId").value(AAPL_ID.toString()))
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.timeframe").value("1m"))
                .andExpect(jsonPath("$.bars[0].close").value(210.12));
    }

    @Test
    void rejectsUnsafeSnapshotLimits() throws Exception {
        mvc.perform(get("/api/v1/market-data/instruments/{instrumentId}/bars", AAPL_ID)
                        .queryParam("limit", "1001"))
                .andExpect(status().isBadRequest());
    }
}

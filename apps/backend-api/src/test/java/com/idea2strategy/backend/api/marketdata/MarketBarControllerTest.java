package com.idea2strategy.backend.api.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.marketdata.MarketBarPort;
import com.idea2strategy.backend.application.marketdata.MarketBarService;
import com.idea2strategy.backend.application.marketdata.MarketBarTimeframe;
import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import com.idea2strategy.backend.api.identity.IdentityAuthExceptionHandler;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MarketBarControllerTest {
    private static final UUID AAPL_ID = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private MockMvc mvc;
    private AtomicInteger requestedLimit;

    @BeforeEach
    void setUp() {
        requestedLimit = new AtomicInteger();
        MarketBarPort port = new MarketBarPort() {
            @Override
            public List<com.idea2strategy.backend.application.marketdata.MarketBar> findRecent(
                    UUID instrumentId, MarketBarTimeframe timeframe, int limit) {
                requestedLimit.set(limit);
                return List.of(new com.idea2strategy.backend.application.marketdata.MarketBar(
                        "event-1", AAPL_ID, "ALPACA", "SIP",
                        Instant.parse("2026-08-06T14:30:00Z"), 1, 0,
                        new BigDecimal("210.00"), new BigDecimal("210.20"),
                        new BigDecimal("209.90"), new BigDecimal("210.12"),
                        new BigDecimal("2500")));
            }
        };
        BasicStrategyCatalogQueryService catalog = org.mockito.Mockito.mock(BasicStrategyCatalogQueryService.class);
        org.mockito.Mockito.when(catalog.getSupportedInstruments()).thenReturn(List.of(
                new SupportedInstrument(AAPL_ID, "STOCK", "XNAS", "USD", "AAPL")));
        var service = new MarketBarService(port, catalog, () -> UUID.randomUUID());
        mvc = MockMvcBuilders.standaloneSetup(new MarketBarController(service))
                .setControllerAdvice(new MarketBarExceptionHandler())
                .build();
    }

    @Test
    void returnsChronologicalStrategyBarsForTheRequestedTimeframe() throws Exception {
        mvc.perform(get("/api/v1/market-data/instruments/{instrumentId}/bars", AAPL_ID)
                        .queryParam("timeframe", "4h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instrumentId").value(AAPL_ID.toString()))
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.timeframe").value("4h"))
                .andExpect(jsonPath("$.bars[0].close").value(210.12));
        assertEquals(400, requestedLimit.get());
    }

    @Test
    void rejectsUnsafeSnapshotLimits() throws Exception {
        mvc.perform(get("/api/v1/market-data/instruments/{instrumentId}/bars", AAPL_ID)
                        .queryParam("limit", "1001"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsServerAnchoredPreviewWindowMetadata() throws Exception {
        mvc.perform(get("/api/v1/market-data/instruments/{instrumentId}/bars", AAPL_ID)
                        .queryParam("timeframe", "4h")
                        .queryParam("window", "1m"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window").value("1m"))
                .andExpect(jsonPath("$.requestedTo").value("2026-08-06T14:30:00Z"))
                .andExpect(jsonPath("$.requestedFrom").value("2026-07-06T14:30:00Z"))
                .andExpect(jsonPath("$.availableFrom").value("2026-08-06T14:30:00Z"))
                .andExpect(jsonPath("$.availableTo").value("2026-08-06T14:30:00Z"))
                .andExpect(jsonPath("$.coverageStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.reasonCode").value("HISTORY_STARTS_AFTER_REQUESTED_WINDOW"));
        assertEquals(1000, requestedLimit.get());
    }

    @Test
    void rejectsUnsupportedPreviewWindow() throws Exception {
        mvc.perform(get("/api/v1/market-data/instruments/{instrumentId}/bars", AAPL_ID)
                        .queryParam("window", "6m"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("window must be one of 1m, 3m"));
    }

    @Test
    void rejectsPreviewWithoutAnAuthenticatedCustomerPrincipalBeforeReadingBars() throws Exception {
        MarketBarPort port = org.mockito.Mockito.mock(MarketBarPort.class);
        BasicStrategyCatalogQueryService catalog = org.mockito.Mockito.mock(BasicStrategyCatalogQueryService.class);
        var service = new MarketBarService(port, catalog, () -> {
            throw new AuthenticationRejectedException("A bearer access JWT is required");
        });
        var anonymousMvc = MockMvcBuilders.standaloneSetup(new MarketBarController(service))
                .setControllerAdvice(new MarketBarExceptionHandler(), new IdentityAuthExceptionHandler())
                .build();

        anonymousMvc.perform(get("/api/v1/market-data/instruments/{instrumentId}/bars", AAPL_ID)
                        .queryParam("window", "1m"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REJECTED"));
        org.mockito.Mockito.verifyNoInteractions(port);
    }
}

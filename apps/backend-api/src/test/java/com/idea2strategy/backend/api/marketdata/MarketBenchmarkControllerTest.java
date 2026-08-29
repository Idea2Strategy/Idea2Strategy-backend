package com.idea2strategy.backend.api.marketdata;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.marketdata.MarketBarPort;
import com.idea2strategy.backend.application.marketdata.MarketBarService;
import com.idea2strategy.backend.application.marketdata.MarketBenchmarkCatalogPort;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MarketBenchmarkControllerTest {
    @Test
    void returnsOnlyTheTwoPublishedIndexBenchmarksWithProductNames() throws Exception {
        UUID spx = UUID.fromString("70000000-0000-4000-8000-000000000500");
        UUID ndx = UUID.fromString("70000000-0000-4000-8000-000000000100");
        MarketBarPort bars = org.mockito.Mockito.mock(MarketBarPort.class);
        BasicStrategyCatalogQueryService strategies = org.mockito.Mockito.mock(BasicStrategyCatalogQueryService.class);
        MarketBenchmarkCatalogPort benchmarks = () -> List.of(
                new SupportedInstrument(spx, "INDEX", "XNYS", "USD", "SPX"),
                new SupportedInstrument(ndx, "INDEX", "XNAS", "USD", "NDX"));
        MarketBarService service = new MarketBarService(bars, strategies, benchmarks, UUID::randomUUID);
        var mvc = MockMvcBuilders.standaloneSetup(new MarketBenchmarkController(service)).build();

        mvc.perform(get("/api/v1/market-data/benchmarks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instruments[0].symbol").value("SPX"))
                .andExpect(jsonPath("$.instruments[0].name").value("S&P 500"))
                .andExpect(jsonPath("$.instruments[1].symbol").value("NDX"))
                .andExpect(jsonPath("$.instruments[1].name").value("NASDAQ-100"));
    }
}

package com.idea2strategy.backend.api.strategy;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.strategy.StrategyCopyCommandService;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StrategyCopyControllerTest {
    private static final UUID SOURCE_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID COPY_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");

    @Test
    void createsAnOwnedDraftCopyWithoutMutatingTheSource() throws Exception {
        var service = mock(StrategyCopyCommandService.class);
        when(service.copyOwnedStrategy(SOURCE_ID)).thenReturn(COPY_ID);
        MockMvc mvc = mvc(service);

        mvc.perform(post("/api/v1/strategies/{strategyId}/copies", SOURCE_ID))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/strategies/" + COPY_ID))
                .andExpect(jsonPath("$.id").value(COPY_ID.toString()));
    }

    @Test
    void hidesMissingOrUnownedSourcesBehindNotFound() throws Exception {
        var service = mock(StrategyCopyCommandService.class);
        when(service.copyOwnedStrategy(SOURCE_ID)).thenThrow(new NoSuchElementException("Strategy not found"));

        mvc(service).perform(post("/api/v1/strategies/{strategyId}/copies", SOURCE_ID))
                .andExpect(status().isNotFound());
    }

    private static MockMvc mvc(StrategyCopyCommandService service) {
        return MockMvcBuilders.standaloneSetup(new StrategyCopyController(service))
                .setControllerAdvice(new StrategyAuthoringExceptionHandler())
                .build();
    }
}

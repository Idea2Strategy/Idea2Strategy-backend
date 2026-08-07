package com.idea2strategy.backend.api.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.strategy.BasicStrategyDraftCommandPort;
import com.idea2strategy.backend.application.strategy.BasicStrategyDraftCommandService;
import com.idea2strategy.backend.application.strategy.StrategyDocumentQueryPort;
import com.idea2strategy.backend.application.strategy.StrategyQueryPort;
import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StrategyDraftControllerTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

    private BasicStrategyDraftCommandPort commandPort;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        commandPort = mock(BasicStrategyDraftCommandPort.class);
        CurrentPrincipal principal = new CurrentPrincipal() {
            @Override
            public UUID accountId() {
                return OWNER_ID;
            }

        };
        var service = new BasicStrategyDraftCommandService(
                commandPort,
                mock(StrategyQueryPort.class),
                mock(StrategyDocumentQueryPort.class),
                principal,
                () -> STRATEGY_ID,
                Clock.fixed(NOW, ZoneOffset.UTC),
                event -> {});
        mvc = MockMvcBuilders.standaloneSetup(new StrategyDraftController(service))
                .setControllerAdvice(new StrategyAuthoringExceptionHandler())
                .build();
    }

    @Test
    void createsAnOwnedBasicDraftWithAnEmptyDocument() throws Exception {
        mvc.perform(post("/api/v1/strategies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Opening Range","description":"Private draft","mode":"BASIC"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/strategies/" + STRATEGY_ID))
                .andExpect(jsonPath("$.id").value(STRATEGY_ID.toString()))
                .andExpect(jsonPath("$.mode").value("BASIC"));

        ArgumentCaptor<Strategy> strategy = ArgumentCaptor.forClass(Strategy.class);
        ArgumentCaptor<StrategyDocument> document = ArgumentCaptor.forClass(StrategyDocument.class);
        verify(commandPort).create(strategy.capture(), document.capture());
        assertThat(strategy.getValue().ownerAccountId()).isEqualTo(OWNER_ID);
        assertThat(document.getValue().strategyId()).isEqualTo(STRATEGY_ID);
        assertThat(document.getValue().semanticDocument()).isEqualTo("{\"groups\":[],\"mode\":\"BASIC\"}");
        assertThat(document.getValue().editSequence()).isZero();
    }

    @Test
    void rejectsUnsupportedModesAndInvalidNames() throws Exception {
        mvc.perform(post("/api/v1/strategies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pro draft\",\"mode\":\"PRO\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/strategies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"mode\":\"BASIC\"}"))
                .andExpect(status().isBadRequest());

        verify(commandPort, org.mockito.Mockito.never()).create(any(), any());
    }
}

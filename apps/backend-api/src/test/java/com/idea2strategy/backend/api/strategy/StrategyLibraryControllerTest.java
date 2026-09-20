package com.idea2strategy.backend.api.strategy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.strategy.StrategyLibraryItem;
import com.idea2strategy.backend.application.strategy.StrategyLibraryItemKind;
import com.idea2strategy.backend.application.strategy.StrategyLibraryQueryPort;
import com.idea2strategy.backend.application.strategy.StrategyLibraryQueryService;
import com.idea2strategy.backend.domain.strategy.StrategyMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StrategyLibraryControllerTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    private MockMvc mvc;
    private AtomicReference<StrategyLibraryItemKind> observedKind;

    @BeforeEach
    void setUp() {
        observedKind = new AtomicReference<>();
        StrategyLibraryQueryPort port = (ownerId, snapshotAt, after, limit, kind) -> {
            observedKind.set(kind);
            return List.of(new StrategyLibraryItem(
                STRATEGY_ID,
                StrategyLibraryItemKind.DRAFT,
                StrategyMode.BASIC,
                "Momentum",
                "Private draft",
                "DRAFT",
                "PASSED",
                null,
                true,
                NOW.minusSeconds(1),
                null,
                2,
                List.of("AAPL")));
        };
        var service = new StrategyLibraryQueryService(
                port, () -> OWNER_ID, Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new StrategyLibraryController(service))
                .setControllerAdvice(new StrategyLibraryExceptionHandler())
                .build();
    }

    @Test
    void exposesPermissionScopedLibraryWithStableWireValues() throws Exception {
        mvc.perform(get("/api/v1/strategies").queryParam("limit", "20").queryParam("kind", "draft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(STRATEGY_ID.toString()))
                .andExpect(jsonPath("$.items[0].kind").value("draft"))
                .andExpect(jsonPath("$.items[0].mode").value("BASIC"))
                .andExpect(jsonPath("$.items[0].validationStatus").value("PASSED"))
                .andExpect(jsonPath("$.items[0].editable").value(true))
                .andExpect(jsonPath("$.items[0].blockCount").value(2))
                .andExpect(jsonPath("$.items[0].symbols[0]").value("AAPL"))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        assert observedKind.get() == StrategyLibraryItemKind.DRAFT;
    }

    @Test
    void returnsBadRequestForInvalidPagination() throws Exception {
        mvc.perform(get("/api/v1/strategies").queryParam("limit", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/strategies").queryParam("cursor", "tampered"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/strategies").queryParam("kind", "unknown"))
                .andExpect(status().isBadRequest());
    }
}

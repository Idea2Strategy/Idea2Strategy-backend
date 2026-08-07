package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class StrategyReleaseInputCatalogQueryServiceTest {
    @Test
    void readsInputsAtOneExplicitServerObservationTime() {
        Instant now = Instant.parse("2026-08-07T12:00:00Z");
        var service = new StrategyReleaseInputCatalogQueryService(
                observedAt -> new StrategyReleaseInputCatalog(List.of(), List.of(), observedAt),
                Clock.fixed(now, ZoneOffset.UTC));

        assertThat(service.getSelectable().observedAt()).isEqualTo(now);
    }
}

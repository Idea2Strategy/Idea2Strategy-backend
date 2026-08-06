package com.idea2strategy.backend.application.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record DashboardSnapshot(Instant generatedAt, List<DashboardBotView> bots) {
    public DashboardSnapshot {
        Objects.requireNonNull(generatedAt, "generatedAt");
        bots = List.copyOf(bots);
    }
}

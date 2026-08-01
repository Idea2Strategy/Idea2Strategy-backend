package com.idea2strategy.backend.application.performance;

import com.idea2strategy.backend.domain.performance.BotCurrentPerformance;
import java.util.Objects;

public final class BotCurrentPerformanceCommandService {
    private final BotCurrentPerformanceCommandPort commandPort;

    public BotCurrentPerformanceCommandService(BotCurrentPerformanceCommandPort commandPort) {
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
    }

    public void save(BotCurrentPerformance performance) {
        commandPort.save(Objects.requireNonNull(performance, "performance"));
    }
}

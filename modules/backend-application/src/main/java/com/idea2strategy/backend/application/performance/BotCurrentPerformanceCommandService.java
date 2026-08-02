package com.idea2strategy.backend.application.performance;

import java.util.Objects;

public final class BotCurrentPerformanceCommandService {
    private final BotCurrentPerformanceCommandPort commandPort;
    private final LivePerformanceProjectionCalculator calculator;

    public BotCurrentPerformanceCommandService(BotCurrentPerformanceCommandPort commandPort) {
        this(commandPort, new LivePerformanceProjectionCalculator());
    }

    BotCurrentPerformanceCommandService(
            BotCurrentPerformanceCommandPort commandPort,
            LivePerformanceProjectionCalculator calculator) {
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
    }

    public ProjectionWriteDecision project(LivePerformanceProjectionInput input) {
        return commandPort.save(calculator.calculate(input));
    }
}

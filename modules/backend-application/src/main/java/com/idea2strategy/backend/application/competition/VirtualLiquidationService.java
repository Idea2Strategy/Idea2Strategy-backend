package com.idea2strategy.backend.application.competition;

import java.time.Clock;
import java.util.Objects;

public final class VirtualLiquidationService {
    private final VirtualLiquidationContextPort contextPort;
    private final VirtualLiquidationQuotePort quotePort;
    private final VirtualLiquidationResultPort resultPort;
    private final VirtualLiquidationPerformanceCalculator calculator;
    private final Clock clock;

    public VirtualLiquidationService(
            VirtualLiquidationContextPort contextPort,
            VirtualLiquidationQuotePort quotePort,
            VirtualLiquidationResultPort resultPort) {
        this(contextPort, quotePort, resultPort, new VirtualLiquidationPerformanceCalculator(), Clock.systemUTC());
    }

    public VirtualLiquidationService(
            VirtualLiquidationContextPort contextPort,
            VirtualLiquidationQuotePort quotePort,
            VirtualLiquidationResultPort resultPort,
            VirtualLiquidationPerformanceCalculator calculator,
            Clock clock) {
        this.contextPort = Objects.requireNonNull(contextPort, "contextPort");
        this.quotePort = Objects.requireNonNull(quotePort, "quotePort");
        this.resultPort = Objects.requireNonNull(resultPort, "resultPort");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public VirtualLiquidationWriteDecision finalizeEvaluation(VirtualLiquidationRequest request) {
        VirtualLiquidationContext context = contextPort.load(Objects.requireNonNull(request, "request"));
        var now = clock.instant();
        if (now.isBefore(context.endsAt())) {
            throw new VirtualLiquidationConflictException("official evaluation cutoff has not been reached");
        }
        VirtualLiquidationQuote quote = quotePort.load(context);
        VirtualLiquidationPerformance performance = calculator.calculate(context, quote);
        return resultPort.save(context, performance, now);
    }
}

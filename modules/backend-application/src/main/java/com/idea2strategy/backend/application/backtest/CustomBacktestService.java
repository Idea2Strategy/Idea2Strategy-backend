package com.idea2strategy.backend.application.backtest;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.time.Clock;
import java.util.Objects;

public final class CustomBacktestService {
    private final CustomBacktestCommandPort port;
    private final CurrentPrincipal principal;
    private final Clock clock;

    public CustomBacktestService(CustomBacktestCommandPort port, CurrentPrincipal principal, Clock clock) {
        this.port = Objects.requireNonNull(port, "port");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BacktestRequestReceipt request(CustomBacktestCommand command) {
        return port.enqueue(principal.accountId(), Objects.requireNonNull(command, "command"), clock.instant());
    }
}

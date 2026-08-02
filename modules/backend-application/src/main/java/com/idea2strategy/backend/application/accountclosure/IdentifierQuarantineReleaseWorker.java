package com.idea2strategy.backend.application.accountclosure;

import java.time.Clock;
import java.util.Objects;

public final class IdentifierQuarantineReleaseWorker {
    private final IdentifierQuarantinePort port;
    private final Clock clock;

    public IdentifierQuarantineReleaseWorker(IdentifierQuarantinePort port, Clock clock) {
        this.port = Objects.requireNonNull(port, "port");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int run(int limit) {
        var now = clock.instant();
        int released = 0;
        for (var identifier : port.findDueIdentifiers(limit, now)) {
            if (!port.hasReuseBlockingLegalHold(identifier.accountId())
                    && port.releaseBindingAndQuarantine(identifier, now)) {
                released++;
            }
        }
        return released;
    }
}

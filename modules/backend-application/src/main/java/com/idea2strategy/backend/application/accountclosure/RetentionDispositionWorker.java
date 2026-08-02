package com.idea2strategy.backend.application.accountclosure;

import java.time.Clock;
import java.util.Objects;

public final class RetentionDispositionWorker {
    public static final String MISSING_EXECUTOR = "PHYSICAL_DISPOSITION_EXECUTOR_MISSING";
    private final RetentionObligationPort port;
    private final Clock clock;

    public RetentionDispositionWorker(RetentionObligationPort port, Clock clock) {
        this.port = Objects.requireNonNull(port, "port");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public int run(int limit) {
        var now = clock.instant();
        port.resumeReleasedHolds(now);
        int processed = 0;
        for (var obligation : port.findDueObligations(limit, now)) {
            if (port.hasActiveLegalHold(obligation.accountId(), obligation.dataCategory())) {
                port.markHeld(obligation.id(), now);
            } else if (obligation.disposition() == RetentionDisposition.RETAIN) {
                port.markCompleted(obligation.id(), now);
            } else {
                // No destructive executor is wired: failing closed is safer than pretending deletion happened.
                port.markFailed(obligation.id(), MISSING_EXECUTOR, now);
            }
            processed++;
        }
        return processed;
    }
}

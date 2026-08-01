package com.idea2strategy.backend.application.botcontrol;

import java.time.Instant;
import java.util.Objects;

public record ExpiredBotStopBatchReport(Instant ranAt, int scanned, int stopsStarted, int skipped) {
    public ExpiredBotStopBatchReport {
        Objects.requireNonNull(ranAt, "ranAt");
        if (scanned < 0 || stopsStarted < 0 || skipped < 0 || stopsStarted + skipped != scanned) {
            throw new IllegalArgumentException("batch report counts must be non-negative and balanced");
        }
    }
}

package com.idea2strategy.backend.application.botcontrol;

import java.time.Instant;

@FunctionalInterface
public interface ExpiredBotStopCommandPort {
    boolean issueExpired(ExpiredBotStopCandidate candidate, Instant requestedAt);
}

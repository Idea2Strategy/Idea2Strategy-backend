package com.idea2strategy.backend.application.botcontrol;

import java.time.Instant;
import java.util.List;

@FunctionalInterface
public interface ExpiredBotStopQueryPort {
    List<ExpiredBotStopCandidate> findExpired(Instant expiredAt, int limit);
}

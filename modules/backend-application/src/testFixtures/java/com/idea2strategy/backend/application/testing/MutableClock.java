package com.idea2strategy.backend.application.testing;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

public final class MutableClock extends Clock {
    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = Objects.requireNonNull(instant, "instant");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public void advanceTo(Instant next) {
        if (next.isBefore(instant)) {
            throw new IllegalArgumentException("Clock cannot move backwards");
        }
        instant = next;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId requestedZone) {
        return new MutableClock(instant, requestedZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}

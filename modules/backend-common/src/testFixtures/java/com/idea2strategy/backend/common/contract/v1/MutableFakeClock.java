package com.idea2strategy.backend.common.contract.v1;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

public final class MutableFakeClock extends Clock {
    private Instant instant;
    private final ZoneId zone;

    public MutableFakeClock(Instant instant, ZoneId zone) {
        this.instant = Objects.requireNonNull(instant, "instant is required");
        this.zone = Objects.requireNonNull(zone, "zone is required");
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId requestedZone) {
        return new MutableFakeClock(instant, requestedZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public void advance(Duration duration) {
        instant = instant.plus(Objects.requireNonNull(duration, "duration is required"));
    }
}

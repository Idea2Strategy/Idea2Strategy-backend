package com.idea2strategy.backend.common.contract.v1;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

public final class ContractTime {
    public static final ZoneId EASTERN = ZoneId.of("America/New_York");

    private ContractTime() {}

    public static ZonedDateTime toEastern(Instant instant) {
        return Objects.requireNonNull(instant, "instant is required").atZone(EASTERN);
    }

    public static Instant toUtc(ZonedDateTime dateTime) {
        return Objects.requireNonNull(dateTime, "dateTime is required").toInstant();
    }
}

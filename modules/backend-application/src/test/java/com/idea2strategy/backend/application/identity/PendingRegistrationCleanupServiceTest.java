package com.idea2strategy.backend.application.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PendingRegistrationCleanupServiceTest {
    @Test
    void purgesRegistrationsWhoseLastVerificationRequestIsSevenDaysOld() {
        Instant now = Instant.parse("2026-08-10T00:00:00Z");
        var observed = new AtomicReference<Call>();
        PendingRegistrationCleanupPort port = (cutoff, purgedAt, limit) -> {
            observed.set(new Call(cutoff, purgedAt, limit));
            return 3;
        };
        var service = new PendingRegistrationCleanupService(
                port, Clock.fixed(now, ZoneOffset.UTC), Duration.ofDays(7));

        assertThat(service.purgeExpired(100)).isEqualTo(3);
        assertThat(observed.get()).isEqualTo(new Call(now.minus(Duration.ofDays(7)), now, 100));
    }

    private record Call(Instant cutoff, Instant purgedAt, int limit) {}
}

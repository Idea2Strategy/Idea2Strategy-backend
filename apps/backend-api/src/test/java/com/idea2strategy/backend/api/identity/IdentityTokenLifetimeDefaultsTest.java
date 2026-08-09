package com.idea2strategy.backend.api.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the token lifetimes the application runs with when nothing overrides them.
 *
 * <p>The wired defaults were previously literals inside {@code @Value} annotations, which no test
 * could see: every codec test constructs a codec with its own explicit lifetime, so all of them would
 * keep passing while the deployed value drifted. Five minutes reached the deployed environment that
 * way and rejected a session in the middle of one continuous task.
 */
class IdentityTokenLifetimeDefaultsTest {

    @Test
    void issuesAccessTokensThatLastAnHourAndRefreshTokensThatLastThirtyDays() {
        assertEquals(
                Duration.ofHours(1),
                Duration.parse(IdentityAuthConfiguration.DEFAULT_ACCESS_LIFETIME),
                "a customer access token must last an hour unless identity.jwt.access-lifetime overrides it");
        assertEquals(
                Duration.ofDays(30),
                Duration.parse(IdentityAuthConfiguration.DEFAULT_REFRESH_LIFETIME),
                "the refresh family lifetime must stay 30 days");
        assertTrue(
                Duration.parse(IdentityAuthConfiguration.DEFAULT_ACCESS_LIFETIME)
                        .compareTo(Duration.parse(IdentityAuthConfiguration.DEFAULT_REFRESH_LIFETIME)) < 0,
                "an access token that outlived its refresh token would make refreshing pointless");
    }

    @Test
    void acceptsAnAccessTokenUpToTheDefaultLifetimeAndRejectsItAfter() {
        // The default read end to end rather than asserted as a string: a token issued at t must still
        // verify at t+59m — which is exactly what five minutes could not do — and must fail at t+61m.
        Instant issuedAt = Instant.parse("2026-08-09T00:00:00Z");
        var clock = new MutableClock(issuedAt);
        var codec = new CustomerJwtCodec(
                new byte[32],
                clock,
                "https://ideatostrategy.com",
                "idea2strategy-api",
                "idea2strategy-refresh",
                Duration.parse(IdentityAuthConfiguration.DEFAULT_ACCESS_LIFETIME));

        UUID accountId = UUID.randomUUID();
        UUID loginIdentityId = UUID.randomUUID();
        String token = codec.issueAccess(accountId, loginIdentityId, 1L, 1L);

        clock.set(issuedAt.plus(Duration.ofMinutes(59)));
        assertEquals(accountId, codec.verifyAccess(token).accountId());

        clock.set(issuedAt.plus(Duration.ofMinutes(61)));
        assertThrows(AuthenticationRejectedException.class, () -> codec.verifyAccess(token));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void set(Instant next) {
            this.now = next;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}

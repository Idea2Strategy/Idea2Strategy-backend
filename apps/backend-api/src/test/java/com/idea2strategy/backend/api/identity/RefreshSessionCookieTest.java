package com.idea2strategy.backend.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RefreshSessionCookieTest {
    private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");

    @Test
    void issuesAHostOnlyHttpOnlySecureStrictCookieWithoutExposingTheTokenElsewhere() {
        var cookies = new RefreshSessionCookie(
                Clock.fixed(NOW, ZoneOffset.UTC), true, "Strict");

        String value = cookies.issue("refresh.jwt.value", NOW.plusSeconds(7200)).toString();

        assertThat(value)
                .startsWith("i2s_refresh=refresh.jwt.value")
                .contains("Path=/api/v1/auth/sessions")
                .contains("Max-Age=7200")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain("Domain=");
    }

    @Test
    void clearsTheExactCookieBoundary() {
        var cookies = new RefreshSessionCookie(
                Clock.fixed(NOW, ZoneOffset.UTC), true, "Strict");

        assertThat(cookies.clear().toString())
                .startsWith("i2s_refresh=")
                .contains("Path=/api/v1/auth/sessions")
                .contains("Max-Age=0")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict");
    }
}

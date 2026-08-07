package com.idea2strategy.backend.api.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.ResponseCookie;

/** Owns the single browser boundary for the rotating customer refresh JWT. */
public final class RefreshSessionCookie {
    public static final String NAME = "i2s_refresh";
    public static final String PATH = "/api/v1/auth/sessions";

    private final Clock clock;
    private final boolean secure;
    private final String sameSite;

    public RefreshSessionCookie(Clock clock, boolean secure, String sameSite) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secure = secure;
        this.sameSite = requireSameSite(sameSite);
    }

    public ResponseCookie issue(String refreshJwt, Instant expiresAt) {
        Objects.requireNonNull(refreshJwt, "refreshJwt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Duration remaining = Duration.between(clock.instant(), expiresAt);
        if (refreshJwt.isBlank() || remaining.isZero() || remaining.isNegative()) {
            throw new IllegalArgumentException("Refresh cookie value and expiry must be valid");
        }
        return base(refreshJwt).maxAge(remaining).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(PATH);
    }

    private static String requireSameSite(String value) {
        if (!"Strict".equals(value) && !"Lax".equals(value)) {
            throw new IllegalArgumentException("Refresh cookie SameSite must be Strict or Lax");
        }
        return value;
    }
}

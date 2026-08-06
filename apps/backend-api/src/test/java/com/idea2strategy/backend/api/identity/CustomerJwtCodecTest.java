package com.idea2strategy.backend.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerJwtCodecTest {
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-08-06T00:00:00Z");
    private static final UUID ACCOUNT = UUID.fromString("a2200000-0000-4000-8000-000000000001");
    private static final UUID SESSION = UUID.fromString("a2200000-0000-4000-8000-000000000002");

    @Test
    void issuesTypedAudienceBoundTokensAndRejectsTampering() {
        var codec = codec(Clock.fixed(NOW, ZoneOffset.UTC));

        String access = codec.issueAccess(ACCOUNT, SESSION);
        String refresh = codec.issueRefresh(ACCOUNT, SESSION, "server-session-secret", NOW.plus(Duration.ofHours(12)));

        assertThat(codec.verifyAccess(access).accountId()).isEqualTo(ACCOUNT);
        assertThat(codec.verifyAccess(access).sessionId()).isEqualTo(SESSION);
        assertThat(codec.verifyRefresh(refresh).sessionSecret()).isEqualTo("server-session-secret");
        assertThatThrownBy(() -> codec.verifyAccess(refresh)).isInstanceOf(AuthenticationRejectedException.class);
        assertThatThrownBy(() -> codec.verifyAccess(access.substring(0, access.length() - 1) + "x"))
                .isInstanceOf(AuthenticationRejectedException.class);
    }

    @Test
    void rejectsExpiredAccessTokens() {
        String token = codec(Clock.fixed(NOW, ZoneOffset.UTC)).issueAccess(ACCOUNT, SESSION);
        var afterExpiry = codec(Clock.fixed(NOW.plus(Duration.ofMinutes(6)), ZoneOffset.UTC));

        assertThatThrownBy(() -> afterExpiry.verifyAccess(token))
                .isInstanceOf(AuthenticationRejectedException.class);
    }

    private static CustomerJwtCodec codec(Clock clock) {
        return new CustomerJwtCodec(KEY, clock, "https://ideatostrategy.com",
                "idea2strategy-api", "idea2strategy-refresh", Duration.ofMinutes(5));
    }
}

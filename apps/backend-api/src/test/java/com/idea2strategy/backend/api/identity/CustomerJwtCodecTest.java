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
    private static final UUID LOGIN_IDENTITY = UUID.fromString("a2200000-0000-4000-8000-000000000002");
    private static final UUID REFRESH_FAMILY = UUID.fromString("a2200000-0000-4000-8000-000000000003");

    @Test
    void issuesTypedAudienceBoundTokensAndRejectsTampering() {
        var codec = codec(Clock.fixed(NOW, ZoneOffset.UTC));

        String access = codec.issueAccess(ACCOUNT, LOGIN_IDENTITY, 4, 7L);
        String refresh = codec.issueRefresh(
                ACCOUNT, REFRESH_FAMILY, LOGIN_IDENTITY, 4, 7L,
                "refresh-token-secret", NOW.plus(Duration.ofDays(30)));

        assertThat(codec.verifyAccess(access).accountId()).isEqualTo(ACCOUNT);
        assertThat(codec.verifyAccess(access).loginIdentityId()).isEqualTo(LOGIN_IDENTITY);
        assertThat(codec.verifyAccess(access).authEpoch()).isEqualTo(4);
        assertThat(codec.verifyAccess(access).credentialVersion()).isEqualTo(7L);
        assertThat(codec.verifyRefresh(refresh).familyId()).isEqualTo(REFRESH_FAMILY);
        assertThat(codec.verifyRefresh(refresh).tokenSecret()).isEqualTo("refresh-token-secret");
        assertThatThrownBy(() -> codec.verifyAccess(refresh)).isInstanceOf(AuthenticationRejectedException.class);
        assertThatThrownBy(() -> codec.verifyAccess(access.substring(0, access.length() - 1) + "x"))
                .isInstanceOf(AuthenticationRejectedException.class);
    }

    @Test
    void rejectsExpiredAccessTokens() {
        String token = codec(Clock.fixed(NOW, ZoneOffset.UTC)).issueAccess(ACCOUNT, LOGIN_IDENTITY, 4, 7L);
        var afterExpiry = codec(Clock.fixed(NOW.plus(Duration.ofMinutes(6)), ZoneOffset.UTC));

        assertThatThrownBy(() -> afterExpiry.verifyAccess(token))
                .isInstanceOf(AuthenticationRejectedException.class);
    }

    private static CustomerJwtCodec codec(Clock clock) {
        return new CustomerJwtCodec(KEY, clock, "https://ideatostrategy.com",
                "idea2strategy-api", "idea2strategy-refresh", Duration.ofMinutes(5));
    }
}

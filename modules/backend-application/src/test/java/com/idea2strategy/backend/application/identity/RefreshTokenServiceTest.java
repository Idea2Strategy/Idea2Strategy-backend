package com.idea2strategy.backend.application.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T03:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID LOGIN_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID FAMILY_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");

    @Test
    void rotatesTheCurrentRefreshSecretAndReturnsSecurityClaimsFromStoredState() {
        var repository = new Repository(session(null, false));
        var service = service(repository);

        RotatedRefreshToken rotated = service.rotate(FAMILY_ID, "current-digest", UUID.randomUUID());

        assertThat(rotated.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(rotated.loginIdentityId()).isEqualTo(LOGIN_ID);
        assertThat(rotated.authEpoch()).isEqualTo(3);
        assertThat(rotated.credentialVersion()).isEqualTo(4L);
        assertThat(rotated.familyId()).isEqualTo(FAMILY_ID);
        assertThat(rotated.tokenSecret()).isEqualTo("replacement-secret");
        assertThat(repository.previousDigest).isEqualTo("current-digest");
        assertThat(repository.replacementDigest).isEqualTo("replacement-digest");
    }

    @Test
    void reuseOfAnAlreadyRotatedRefreshTokenRevokesItsFamily() {
        var repository = new Repository(session(null, false));
        repository.rotationSucceeds = false;

        assertThatThrownBy(() -> service(repository).rotate(FAMILY_ID, "used-digest", UUID.randomUUID()))
                .isInstanceOf(AuthenticationRejectedException.class)
                .hasMessageContaining("already used");
        assertThat(repository.revokedFamilyId).isEqualTo(FAMILY_ID);
        assertThat(repository.revocationReason).isEqualTo("REFRESH_TOKEN_REUSE");
    }

    @Test
    void rejectsRevokedOrSanctionedRefreshFamilies() {
        var revoked = new Repository(session(NOW.minusSeconds(1), false));
        assertThatThrownBy(() -> service(revoked).rotate(FAMILY_ID, "digest", UUID.randomUUID()))
                .isInstanceOf(AuthenticationRejectedException.class);
        assertThat(revoked.eventReason).isEqualTo("REVOKED");

        var sanctioned = new Repository(session(null, true));
        assertThatThrownBy(() -> service(sanctioned).rotate(FAMILY_ID, "digest", UUID.randomUUID()))
                .isInstanceOf(SanctionedAccountAccessException.class);
        assertThat(sanctioned.eventReason).isEqualTo("ACTIVE_ACCOUNT_SANCTION");
    }

    @Test
    void logoutAllRevokesEveryFamilyAndBumpsTheSecurityEpochThroughThePort() {
        var repository = new Repository(session(null, false));
        repository.revokeAllCount = 3;

        assertThat(service(repository).revokeAll(FAMILY_ID, "current-digest", UUID.randomUUID()))
                .isEqualTo(3);
        assertThat(repository.revocationReason).isEqualTo("LOGOUT_ALL");
    }

    private static RefreshTokenService service(Repository repository) {
        return new RefreshTokenService(
                repository,
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> new RefreshTokenSecret("replacement-secret", "replacement-digest"),
                Duration.ofDays(30));
    }

    private static StoredRefreshTokenFamily session(Instant revokedAt, boolean activeSanction) {
        return new StoredRefreshTokenFamily(
                FAMILY_ID, ACCOUNT_ID, LOGIN_ID, 3, 3, 4L, 4L,
                AccountLifecycleStatus.ACTIVE, LoginIdentityStatus.ACTIVE,
                NOW.minusSeconds(120), NOW.minusSeconds(10), NOW.plusSeconds(3600),
                revokedAt, activeSanction);
    }

    private static final class Repository implements RefreshTokenFamilyQueryPort, RefreshTokenFamilyCommandPort {
        private final StoredRefreshTokenFamily current;
        private boolean rotationSucceeds = true;
        private int revokeAllCount;
        private UUID revokedFamilyId;
        private String revocationReason;
        private String eventReason;
        private String previousDigest;
        private String replacementDigest;

        private Repository(StoredRefreshTokenFamily current) {
            this.current = current;
        }

        @Override public Optional<StoredRefreshTokenFamily> findByTokenDigest(String digest) {
            return "current-digest".equals(digest) ? Optional.of(current) : Optional.empty();
        }
        @Override public Optional<StoredRefreshTokenFamily> findById(UUID id) {
            return current.id().equals(id) ? Optional.of(current) : Optional.empty();
        }
        @Override public boolean revoke(UUID accountId, UUID id, String reason, UUID correlationId, Instant now) {
            revokedFamilyId = id;
            revocationReason = reason;
            return true;
        }
        @Override public boolean rotate(
                UUID accountId, UUID id, String previousTokenDigest, String replacementTokenDigest,
                Instant expiresAt, UUID correlationId, Instant now) {
            previousDigest = previousTokenDigest;
            replacementDigest = replacementTokenDigest;
            return rotationSucceeds;
        }
        @Override public void recordEvent(
                UUID accountId, UUID loginIdentityId, UUID id, String eventType, String reason,
                UUID correlationId, Instant now) {
            eventReason = reason;
        }
        @Override public int revokeAll(UUID accountId, String reason, UUID correlationId, Instant now) {
            revocationReason = reason;
            return revokeAllCount;
        }
    }
}

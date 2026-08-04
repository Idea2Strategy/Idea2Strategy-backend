package com.idea2strategy.backend.application.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SessionManagementServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T03:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID LOGIN_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID CURRENT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_ID = UUID.fromString("30000000-0000-4000-8000-000000000002");

    @Test
    void rejectsARevokedSession() {
        var repository = new Repository(session(CURRENT_ID, NOW.minusSeconds(1)));
        var service = service(repository);

        assertThatThrownBy(() -> service.authenticate("current-digest"))
                .isInstanceOf(AuthenticationRejectedException.class);
        assertThat(repository.eventType).isEqualTo("SESSION_REJECTED");
        assertThat(repository.eventReason).isEqualTo("REVOKED");
    }

    @Test
    void rejectsAValidSessionWhenTheAccountHasAnActiveSanction() {
        var repository = new Repository(session(CURRENT_ID, null, true));
        var service = service(repository);

        assertThatThrownBy(() -> service.authenticate("current-digest"))
                .isInstanceOf(SanctionedAccountAccessException.class)
                .hasMessageContaining("appeal");
        assertThat(repository.eventType).isEqualTo("SESSION_REJECTED");
        assertThat(repository.eventReason).isEqualTo("ACTIVE_ACCOUNT_SANCTION");
    }

    @Test
    void permitsOnlyExplicitRestrictedAccessForAnActiveSanction() {
        var repository = new Repository(session(CURRENT_ID, null, true));
        var service = service(repository);

        assertThat(service.authenticate(
                        "current-digest", UUID.randomUUID(), CustomerAccessScope.APPEAL))
                .isEqualTo(new AuthenticatedSession(ACCOUNT_ID, CURRENT_ID, true));
        assertThat(repository.eventType).isEqualTo("SANCTION_RESTRICTED_ACCESS_VALIDATED");
        assertThat(repository.eventReason).isEqualTo("APPEAL");
    }

    @Test
    void listsOnlySafeActiveSessionMetadataAndMarksTheCurrentSession() {
        var repository = new Repository(session(CURRENT_ID, null));
        repository.active.add(new ActiveSession(
                CURRENT_ID, "laptop", NOW.minusSeconds(120), NOW.minusSeconds(10), NOW.plusSeconds(3600)));
        repository.active.add(new ActiveSession(
                OTHER_ID, "phone", NOW.minusSeconds(60), NOW.minusSeconds(5), NOW.plusSeconds(3600)));

        var sessions = service(repository).list("current-digest");

        assertThat(sessions).extracting(SessionView::sessionId).containsExactly(CURRENT_ID, OTHER_ID);
        assertThat(sessions).extracting(SessionView::current).containsExactly(true, false);
    }

    @Test
    void remotelyRevokesOnlyAnotherOwnedSession() {
        var repository = new Repository(session(CURRENT_ID, null));

        service(repository).revokeOther("current-digest", OTHER_ID, UUID.randomUUID());

        assertThat(repository.revokedSessionId).isEqualTo(OTHER_ID);
        assertThat(repository.revocationReason).isEqualTo("REMOTE_LOGOUT");
    }

    @Test
    void cannotUseRemoteLogoutToRevokeTheCurrentSession() {
        var repository = new Repository(session(CURRENT_ID, null));

        assertThatThrownBy(() -> service(repository)
                        .revokeOther("current-digest", CURRENT_ID, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SessionManagementService service(Repository repository) {
        return new SessionManagementService(repository, repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static StoredSession session(UUID id, Instant revokedAt) {
        return session(id, revokedAt, false);
    }

    private static StoredSession session(UUID id, Instant revokedAt, boolean activeSanction) {
        return new StoredSession(
                id,
                ACCOUNT_ID,
                LOGIN_ID,
                3,
                3,
                4L,
                4L,
                AccountLifecycleStatus.ACTIVE,
                LoginIdentityStatus.ACTIVE,
                "laptop",
                NOW.minusSeconds(120),
                NOW.minusSeconds(10),
                NOW.plusSeconds(3600),
                revokedAt,
                activeSanction);
    }

    private static final class Repository implements SessionQueryPort, SessionCommandPort {
        private final StoredSession current;
        private final List<ActiveSession> active = new ArrayList<>();
        private UUID revokedSessionId;
        private String revocationReason;
        private String eventType;
        private String eventReason;

        private Repository(StoredSession current) {
            this.current = current;
        }

        @Override
        public Optional<StoredSession> findByTokenDigest(String tokenDigest) {
            return "current-digest".equals(tokenDigest) ? Optional.of(current) : Optional.empty();
        }

        @Override
        public List<ActiveSession> findActiveByAccountId(UUID accountId, Instant now) {
            return List.copyOf(active);
        }

        @Override
        public boolean revoke(UUID accountId, UUID sessionId, String reason, UUID correlationId, Instant now) {
            revokedSessionId = sessionId;
            revocationReason = reason;
            return true;
        }

        @Override
        public boolean rotate(
                UUID accountId,
                UUID sessionId,
                String previousTokenDigest,
                String replacementTokenDigest,
                Instant expiresAt,
                UUID correlationId,
                Instant now) {
            return true;
        }

        @Override
        public void recordEvent(
                UUID accountId,
                UUID loginIdentityId,
                UUID sessionId,
                String eventType,
                String reason,
                UUID correlationId,
                Instant now) {
            this.eventType = eventType;
            this.eventReason = reason;
        }

        @Override
        public int revokeAll(UUID accountId, String reason, UUID correlationId, Instant now) {
            return active.size();
        }
    }
}

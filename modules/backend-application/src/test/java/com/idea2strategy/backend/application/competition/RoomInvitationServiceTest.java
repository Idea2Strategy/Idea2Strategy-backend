package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.domain.competition.RoomInvitationCredentialType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoomInvitationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
    private static final UUID ROOM_ID = UUID.fromString("72000000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("72000000-0000-4000-8000-000000000002");
    private static final UUID INVITATION_ID = UUID.fromString("72000000-0000-4000-8000-000000000003");

    @Test
    void returnsTheSecretOnceWhilePersistingOnlyItsDigest() {
        var port = new StubInvitationPort();
        var service = service(port);

        var issued = service.issue(ROOM_ID, RoomInvitationCredentialType.CODE, Duration.ofHours(1));

        assertThat(issued.secret()).isEqualTo("one-time-secret");
        assertThat(port.issuedRequest.credentialDigest()).isEqualTo("digest-only");
        assertThat(port.issuedRequest.credentialDigest()).doesNotContain("one-time-secret");
    }

    @Test
    void rejectsUnauthorizedIssueAndUnavailableOrReusedCredentials() {
        var port = new StubInvitationPort();
        port.allowIssue = false;
        var service = service(port);

        assertThatThrownBy(() -> service.issue(
                        ROOM_ID, RoomInvitationCredentialType.LINK, Duration.ofMinutes(30)))
                .isInstanceOf(RoomInvitationAccessException.class);

        assertThatThrownBy(() -> service.consume("expired-or-used"))
                .isInstanceOf(RoomInvitationUnavailableException.class);
    }

    private static RoomInvitationService service(StubInvitationPort port) {
        return new RoomInvitationService(
                port,
                () -> ACCOUNT_ID,
                () -> new RoomInvitationSecret("one-time-secret", "digest-only"),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> INVITATION_ID);
    }

    private static final class StubInvitationPort implements RoomInvitationPort {
        private RoomInvitationIssueRequest issuedRequest;
        private boolean allowIssue = true;
        private final ArrayList<UUID> revoked = new ArrayList<>();

        @Override
        public Optional<RoomInvitationRecord> issue(RoomInvitationIssueRequest request) {
            issuedRequest = request;
            return allowIssue
                    ? Optional.of(new RoomInvitationRecord(
                            request.id(),
                            request.roomId(),
                            request.credentialType(),
                            request.requestedExpiresAt()))
                    : Optional.empty();
        }

        @Override
        public boolean revoke(UUID roomId, UUID invitationId, UUID actorAccountId, Instant revokedAt) {
            revoked.add(invitationId);
            return true;
        }

        @Override
        public Optional<ConsumedRoomInvitation> consume(String credentialDigest, Instant consumedAt) {
            return Optional.empty();
        }
    }
}

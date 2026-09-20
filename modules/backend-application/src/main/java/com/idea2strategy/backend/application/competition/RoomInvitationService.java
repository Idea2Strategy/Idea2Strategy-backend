package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.domain.competition.RoomInvitationCredentialType;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class RoomInvitationService {
    private static final Duration MINIMUM_VALIDITY = Duration.ofMinutes(1);
    private static final Duration MAXIMUM_VALIDITY = Duration.ofDays(7);

    private final RoomInvitationPort port;
    private final CurrentPrincipal principal;
    private final RoomInvitationSecretIssuer secretIssuer;
    private final Clock clock;
    private final Supplier<UUID> invitationIdSupplier;

    public RoomInvitationService(
            RoomInvitationPort port,
            CurrentPrincipal principal,
            RoomInvitationSecretIssuer secretIssuer,
            Clock clock,
            Supplier<UUID> invitationIdSupplier) {
        this.port = Objects.requireNonNull(port, "port");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.secretIssuer = Objects.requireNonNull(secretIssuer, "secretIssuer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.invitationIdSupplier = Objects.requireNonNull(invitationIdSupplier, "invitationIdSupplier");
    }

    public IssuedRoomInvitation issue(
            UUID roomId, RoomInvitationCredentialType credentialType, Duration requestedValidity) {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(credentialType, "credentialType");
        Objects.requireNonNull(requestedValidity, "requestedValidity");
        if (requestedValidity.compareTo(MINIMUM_VALIDITY) < 0
                || requestedValidity.compareTo(MAXIMUM_VALIDITY) > 0) {
            throw new IllegalArgumentException("Invitation validity must be between 1 minute and 7 days");
        }
        var now = clock.instant();
        var secret = secretIssuer.issue();
        var request = new RoomInvitationIssueRequest(
                invitationIdSupplier.get(),
                roomId,
                principal.accountId(),
                credentialType,
                secret.digest(),
                now,
                now.plus(requestedValidity));
        var record = port.issue(request).orElseThrow(RoomInvitationAccessException::new);
        return new IssuedRoomInvitation(
                record.id(), record.roomId(), record.credentialType(), secret.rawValue(), record.expiresAt());
    }

    public void revoke(UUID roomId, UUID invitationId) {
        if (!port.revoke(
                Objects.requireNonNull(roomId, "roomId"),
                Objects.requireNonNull(invitationId, "invitationId"),
                principal.accountId(),
                clock.instant())) {
            throw new RoomInvitationAccessException();
        }
    }

    public ConsumedRoomInvitation consume(String rawCredential) {
        if (rawCredential == null || rawCredential.isBlank() || rawCredential.length() > 200) {
            throw new RoomInvitationUnavailableException();
        }
        return port.consume(
                        RoomInvitationSecrets.digest(rawCredential), principal.accountId(), clock.instant())
                .orElseThrow(RoomInvitationUnavailableException::new);
    }
}

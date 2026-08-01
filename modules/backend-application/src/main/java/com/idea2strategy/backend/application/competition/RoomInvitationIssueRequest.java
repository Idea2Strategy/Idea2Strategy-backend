package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.competition.RoomInvitationCredentialType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RoomInvitationIssueRequest(
        UUID id,
        UUID roomId,
        UUID issuerAccountId,
        RoomInvitationCredentialType credentialType,
        String credentialDigest,
        Instant issuedAt,
        Instant requestedExpiresAt) {
    public RoomInvitationIssueRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(issuerAccountId, "issuerAccountId");
        Objects.requireNonNull(credentialType, "credentialType");
        Objects.requireNonNull(credentialDigest, "credentialDigest");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(requestedExpiresAt, "requestedExpiresAt");
    }
}

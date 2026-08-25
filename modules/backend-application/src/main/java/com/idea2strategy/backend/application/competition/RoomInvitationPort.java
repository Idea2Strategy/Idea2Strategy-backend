package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RoomInvitationPort {
    Optional<RoomInvitationRecord> issue(RoomInvitationIssueRequest request);

    boolean revoke(UUID roomId, UUID invitationId, UUID actorAccountId, Instant revokedAt);

    Optional<ConsumedRoomInvitation> consume(
            String credentialDigest, UUID consumerAccountId, Instant consumedAt);
}

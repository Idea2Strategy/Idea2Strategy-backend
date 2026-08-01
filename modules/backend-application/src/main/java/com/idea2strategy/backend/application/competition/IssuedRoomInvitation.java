package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.competition.RoomInvitationCredentialType;
import java.time.Instant;
import java.util.UUID;

public record IssuedRoomInvitation(
        UUID id,
        UUID roomId,
        RoomInvitationCredentialType credentialType,
        String secret,
        Instant expiresAt) {}

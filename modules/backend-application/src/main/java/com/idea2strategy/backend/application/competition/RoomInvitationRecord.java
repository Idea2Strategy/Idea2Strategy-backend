package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.competition.RoomInvitationCredentialType;
import java.time.Instant;
import java.util.UUID;

public record RoomInvitationRecord(
        UUID id, UUID roomId, RoomInvitationCredentialType credentialType, Instant expiresAt) {}

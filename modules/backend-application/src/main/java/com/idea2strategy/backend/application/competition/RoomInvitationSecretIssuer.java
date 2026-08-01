package com.idea2strategy.backend.application.competition;

@FunctionalInterface
public interface RoomInvitationSecretIssuer {
    RoomInvitationSecret issue();
}

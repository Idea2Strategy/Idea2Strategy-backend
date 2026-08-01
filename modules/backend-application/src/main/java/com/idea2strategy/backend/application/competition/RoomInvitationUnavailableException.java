package com.idea2strategy.backend.application.competition;

public final class RoomInvitationUnavailableException extends RuntimeException {
    public RoomInvitationUnavailableException() {
        super("Invitation is invalid, expired, revoked, or already used");
    }
}

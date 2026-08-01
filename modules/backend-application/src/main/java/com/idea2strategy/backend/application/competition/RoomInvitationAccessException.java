package com.idea2strategy.backend.application.competition;

public final class RoomInvitationAccessException extends RuntimeException {
    public RoomInvitationAccessException() {
        super("Only the active secret room owner can manage invitations");
    }
}

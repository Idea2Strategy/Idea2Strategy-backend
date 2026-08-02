package com.idea2strategy.backend.application.competition;

public final class RoomTerminationAccessException extends RuntimeException {
    public RoomTerminationAccessException() {
        super("The actor cannot terminate this room or participation");
    }
}

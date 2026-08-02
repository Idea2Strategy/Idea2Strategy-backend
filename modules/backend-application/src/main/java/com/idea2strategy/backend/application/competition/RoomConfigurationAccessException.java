package com.idea2strategy.backend.application.competition;

public final class RoomConfigurationAccessException extends RuntimeException {
    public RoomConfigurationAccessException() {
        super("The actor cannot configure this room");
    }
}

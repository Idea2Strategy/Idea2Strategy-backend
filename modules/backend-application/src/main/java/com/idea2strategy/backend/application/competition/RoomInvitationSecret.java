package com.idea2strategy.backend.application.competition;

import java.util.Objects;

public record RoomInvitationSecret(String rawValue, String digest) {
    public RoomInvitationSecret {
        Objects.requireNonNull(rawValue, "rawValue");
        Objects.requireNonNull(digest, "digest");
        if (rawValue.isBlank() || digest.isBlank()) {
            throw new IllegalArgumentException("Invitation secret and digest must not be blank");
        }
    }
}

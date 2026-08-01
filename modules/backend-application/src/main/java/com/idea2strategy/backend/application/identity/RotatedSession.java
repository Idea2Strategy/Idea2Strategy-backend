package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.UUID;

public record RotatedSession(UUID sessionId, String sessionToken, Instant expiresAt) {
    @Override
    public String toString() {
        return "RotatedSession[sessionId=" + sessionId + ", sessionToken=REDACTED, expiresAt=" + expiresAt + "]";
    }
}

package com.idea2strategy.backend.api.identity;

import java.time.Instant;
import java.util.UUID;

public interface PasswordResetDeliveryPort {
    void send(UUID accountId, String resetToken, Instant expiresAt);
}

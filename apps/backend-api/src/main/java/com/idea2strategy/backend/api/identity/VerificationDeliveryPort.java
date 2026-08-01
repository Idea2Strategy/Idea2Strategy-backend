package com.idea2strategy.backend.api.identity;

import java.time.Instant;
import java.util.UUID;

public interface VerificationDeliveryPort {
    void send(String email, String verificationToken, Instant expiresAt);

    void send(UUID accountId, String verificationToken, Instant expiresAt);
}

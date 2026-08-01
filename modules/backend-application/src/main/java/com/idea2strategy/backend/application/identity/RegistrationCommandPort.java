package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.UUID;

public interface RegistrationCommandPort {
    void createPending(PendingRegistration registration);

    VerificationOutcome consumeVerification(String tokenDigest, Instant consumedAt, UUID correlationId);

    void replaceVerification(VerificationReplacement replacement);
}

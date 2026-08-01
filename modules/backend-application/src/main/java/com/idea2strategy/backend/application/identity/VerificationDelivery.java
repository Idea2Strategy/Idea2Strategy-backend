package com.idea2strategy.backend.application.identity;

import java.time.Instant;

public record VerificationDelivery(String verificationToken, Instant expiresAt) {}

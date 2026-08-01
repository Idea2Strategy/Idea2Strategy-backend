package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.UUID;

public record SignupResult(UUID accountId, String verificationToken, Instant expiresAt) {}

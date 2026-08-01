package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PendingRegistration(
        UUID accountId,
        UUID loginIdentityId,
        UUID verificationRequestId,
        ProtectedEmail email,
        PasswordHash password,
        String verificationTokenDigest,
        Instant requestedAt,
        Instant expiresAt,
        UUID correlationId,
        String requestIpPrefix) {
    public PendingRegistration {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(loginIdentityId, "loginIdentityId");
        Objects.requireNonNull(verificationRequestId, "verificationRequestId");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(verificationTokenDigest, "verificationTokenDigest");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    @Override
    public String toString() {
        return "PendingRegistration[accountId=" + accountId + ", verificationRequestId="
                + verificationRequestId + ", protected=REDACTED]";
    }
}

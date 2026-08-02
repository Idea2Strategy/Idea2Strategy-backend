package com.idea2strategy.backend.application.identity;

import com.idea2strategy.backend.domain.identity.AccountPreferences;
import com.idea2strategy.backend.domain.identity.ThemePreference;
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
        String requestIpPrefix,
        AccountPreferences preferences) {
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
        Objects.requireNonNull(preferences, "preferences");
    }

    public PendingRegistration(
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
        this(
                accountId,
                loginIdentityId,
                verificationRequestId,
                email,
                password,
                verificationTokenDigest,
                requestedAt,
                expiresAt,
                correlationId,
                requestIpPrefix,
                new AccountPreferences("ko", "America/New_York", ThemePreference.SYSTEM, requestedAt));
    }

    @Override
    public String toString() {
        return "PendingRegistration[accountId=" + accountId + ", verificationRequestId="
                + verificationRequestId + ", protected=REDACTED]";
    }
}

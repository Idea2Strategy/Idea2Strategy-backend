package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;

public record PasswordLoginAccount(
        UUID accountId,
        UUID loginIdentityId,
        AccountLifecycleStatus accountStatus,
        EmailStatus emailStatus,
        LoginIdentityStatus loginIdentityStatus,
        String passwordHash,
        long credentialVersion,
        long authEpoch) {
    public PasswordLoginAccount {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(loginIdentityId, "loginIdentityId");
        Objects.requireNonNull(accountStatus, "accountStatus");
        Objects.requireNonNull(emailStatus, "emailStatus");
        Objects.requireNonNull(loginIdentityStatus, "loginIdentityStatus");
        Objects.requireNonNull(passwordHash, "passwordHash");
        if (credentialVersion < 1 || authEpoch < 1) {
            throw new IllegalArgumentException("Credential version and auth epoch must be positive");
        }
    }
}

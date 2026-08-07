package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;

public record CustomerAccessState(
        UUID accountId,
        UUID loginIdentityId,
        long authEpoch,
        Long credentialVersion,
        AccountLifecycleStatus accountStatus,
        LoginIdentityStatus loginIdentityStatus,
        boolean activeSanction) {
    public CustomerAccessState {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(loginIdentityId, "loginIdentityId");
        Objects.requireNonNull(accountStatus, "accountStatus");
        Objects.requireNonNull(loginIdentityStatus, "loginIdentityStatus");
        if (authEpoch < 1 || (credentialVersion != null && credentialVersion < 1)) {
            throw new IllegalArgumentException("Customer security versions must be positive");
        }
    }
}

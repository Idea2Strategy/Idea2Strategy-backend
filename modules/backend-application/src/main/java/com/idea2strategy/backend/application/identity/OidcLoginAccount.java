package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;

public record OidcLoginAccount(
        UUID accountId,
        UUID loginIdentityId,
        AccountLifecycleStatus accountStatus,
        LoginIdentityStatus loginIdentityStatus,
        long authEpoch) {
    public OidcLoginAccount {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(loginIdentityId, "loginIdentityId");
        Objects.requireNonNull(accountStatus, "accountStatus");
        Objects.requireNonNull(loginIdentityStatus, "loginIdentityStatus");
        if (authEpoch < 1) {
            throw new IllegalArgumentException("Auth epoch must be positive");
        }
    }
}

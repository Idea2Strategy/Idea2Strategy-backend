package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;

public record ExistingEmailRegistration(
        UUID accountId,
        AccountLifecycleStatus lifecycleStatus,
        EmailStatus emailStatus) {
    public ExistingEmailRegistration {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(lifecycleStatus, "lifecycleStatus");
        Objects.requireNonNull(emailStatus, "emailStatus");
    }

    public boolean awaitingVerification() {
        return lifecycleStatus == AccountLifecycleStatus.PENDING_VERIFICATION
                && emailStatus == EmailStatus.PENDING_VERIFICATION;
    }
}

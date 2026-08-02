package com.idea2strategy.backend.application.accountsanction;

public final class AccountSanctionIdempotencyConflictException extends RuntimeException {
    public AccountSanctionIdempotencyConflictException() {
        super("The sanction idempotency key was already used for another request");
    }
}

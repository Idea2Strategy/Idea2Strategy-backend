package com.idea2strategy.backend.application.accountsanction;

public final class AccountSanctionAuthenticationRejectedException extends RuntimeException {
    public AccountSanctionAuthenticationRejectedException() {
        super("A trusted operator subject is required");
    }
}

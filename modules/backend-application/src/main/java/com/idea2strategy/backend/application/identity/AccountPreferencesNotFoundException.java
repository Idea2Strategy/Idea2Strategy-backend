package com.idea2strategy.backend.application.identity;

public final class AccountPreferencesNotFoundException extends RuntimeException {
    public AccountPreferencesNotFoundException() {
        super("Account preferences do not exist");
    }
}

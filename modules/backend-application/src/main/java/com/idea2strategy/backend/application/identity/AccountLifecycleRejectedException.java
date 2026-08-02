package com.idea2strategy.backend.application.identity;

public final class AccountLifecycleRejectedException extends RuntimeException {
    private final String code;

    public AccountLifecycleRejectedException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

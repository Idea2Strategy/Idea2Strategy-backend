package com.idea2strategy.backend.application.usercase;

public final class UserCaseRejectedException extends RuntimeException {
    private final String code;

    public UserCaseRejectedException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

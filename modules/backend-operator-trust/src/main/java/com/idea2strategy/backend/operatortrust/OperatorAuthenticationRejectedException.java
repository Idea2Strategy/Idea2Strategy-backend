package com.idea2strategy.backend.operatortrust;

public final class OperatorAuthenticationRejectedException extends RuntimeException {
    private final String code;

    public OperatorAuthenticationRejectedException() {
        this("OPERATOR_AUTHENTICATION_REJECTED");
    }

    public OperatorAuthenticationRejectedException(String code) {
        super(code);
        this.code = code;
    }

    public String code() { return code; }
}

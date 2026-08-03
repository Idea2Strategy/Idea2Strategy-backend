package com.idea2strategy.backend.application.operatorbootstrap;

public final class OperatorBootstrapRejectedException extends RuntimeException {
    private final String code;

    public OperatorBootstrapRejectedException(String code) {
        super(code);
        this.code = code;
    }

    public OperatorBootstrapRejectedException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

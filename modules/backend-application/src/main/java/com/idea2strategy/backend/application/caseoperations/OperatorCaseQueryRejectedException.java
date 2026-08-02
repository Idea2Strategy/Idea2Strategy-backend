package com.idea2strategy.backend.application.caseoperations;

public final class OperatorCaseQueryRejectedException extends RuntimeException {
    private final String code;

    public OperatorCaseQueryRejectedException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

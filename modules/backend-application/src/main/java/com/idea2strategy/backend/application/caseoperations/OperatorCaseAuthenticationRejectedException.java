package com.idea2strategy.backend.application.caseoperations;

public final class OperatorCaseAuthenticationRejectedException extends RuntimeException {
    public OperatorCaseAuthenticationRejectedException() {
        super("OPERATOR_CASE_AUTHENTICATION_REQUIRED");
    }
}

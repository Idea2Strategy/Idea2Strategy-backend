package com.idea2strategy.backend.application.operatorrbac;

public final class OperatorRbacAuthenticationRejectedException extends RuntimeException {
    public OperatorRbacAuthenticationRejectedException() {
        super("OPERATOR_AUTHENTICATION_REQUIRED");
    }
}

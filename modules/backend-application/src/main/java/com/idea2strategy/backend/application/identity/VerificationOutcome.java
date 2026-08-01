package com.idea2strategy.backend.application.identity;

public enum VerificationOutcome {
    VERIFIED,
    EXPIRED,
    ALREADY_USED,
    NOT_FOUND,
    ACCOUNT_NOT_PENDING
}

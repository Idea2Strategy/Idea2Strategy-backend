package com.idea2strategy.backend.application.identity;

public enum PasswordResetOutcome {
    CHANGED,
    NOT_FOUND,
    EXPIRED,
    ALREADY_USED,
    STALE
}

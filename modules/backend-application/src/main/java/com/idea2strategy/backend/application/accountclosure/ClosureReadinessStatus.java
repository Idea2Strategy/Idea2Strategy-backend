package com.idea2strategy.backend.application.accountclosure;

public enum ClosureReadinessStatus {
    FREEZE_REQUESTED,
    FROZEN,
    SETTLEMENT_REQUIRED,
    SETTLED,
    BLOCKED;

    public boolean allowsClosure() {
        return this == FROZEN || this == SETTLED;
    }
}

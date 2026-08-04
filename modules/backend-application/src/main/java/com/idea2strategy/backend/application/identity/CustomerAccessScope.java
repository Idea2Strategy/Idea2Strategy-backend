package com.idea2strategy.backend.application.identity;

/** Explicitly reviewable customer surfaces that remain reachable during an active sanction. */
public enum CustomerAccessScope {
    STANDARD(false),
    APPEAL(true),
    DATA_RIGHTS(true),
    SESSION_TEARDOWN(true);

    private final boolean allowedDuringSanction;

    CustomerAccessScope(boolean allowedDuringSanction) {
        this.allowedDuringSanction = allowedDuringSanction;
    }

    boolean allowedDuringSanction() {
        return allowedDuringSanction;
    }
}

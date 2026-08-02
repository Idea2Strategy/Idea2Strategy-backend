package com.idea2strategy.backend.application.usercase;

public enum UserCaseStatus {
    OPEN,
    NEEDS_INFORMATION,
    UNDER_REVIEW,
    RESOLVED,
    REJECTED;

    public boolean terminal() {
        return this == RESOLVED || this == REJECTED;
    }
}

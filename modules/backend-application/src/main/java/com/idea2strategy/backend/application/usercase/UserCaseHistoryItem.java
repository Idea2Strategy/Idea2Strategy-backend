package com.idea2strategy.backend.application.usercase;

import java.time.Instant;

public record UserCaseHistoryItem(
        Actor actor,
        UserCaseStatus status,
        String message,
        Instant createdAt) {
    public enum Actor {
        CUSTOMER,
        SUPPORT,
        SYSTEM
    }
}

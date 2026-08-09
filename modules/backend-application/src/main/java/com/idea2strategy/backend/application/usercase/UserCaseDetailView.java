package com.idea2strategy.backend.application.usercase;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserCaseDetailView(
        UUID id,
        UserCaseType type,
        UserCaseStatus status,
        String subject,
        String description,
        Instant createdAt,
        Instant updatedAt,
        Instant responseDeadlineAt,
        List<UserCaseHistoryItem> history) {
    public UserCaseDetailView {
        history = List.copyOf(history);
    }
}

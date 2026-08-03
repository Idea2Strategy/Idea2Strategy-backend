package com.idea2strategy.backend.application.caseoperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record CaseResponseDeadlinePolicy(String version, Duration responseWindow) {
    public CaseResponseDeadlinePolicy {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(responseWindow, "responseWindow");
        if (version.isBlank() || responseWindow.isZero() || responseWindow.isNegative()) {
            throw new IllegalArgumentException("CASE_DEADLINE_POLICY_INVALID");
        }
    }

    public Instant deadlineFrom(Instant databaseNow) {
        return Objects.requireNonNull(databaseNow, "databaseNow").plus(responseWindow);
    }
}

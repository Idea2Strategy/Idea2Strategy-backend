package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RoomParticipationAdmissionContext(
        UUID roomId,
        UUID ownerAccountId,
        Instant admittedAt,
        Instant executionEligibleFrom,
        RoomSubmissionTiming submissionTiming,
        RoomBotLaunchRules launchRules) {
    public RoomParticipationAdmissionContext {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        Objects.requireNonNull(admittedAt, "admittedAt");
        Objects.requireNonNull(executionEligibleFrom, "executionEligibleFrom");
        Objects.requireNonNull(submissionTiming, "submissionTiming");
        Objects.requireNonNull(launchRules, "launchRules");
    }
}

package com.idea2strategy.backend.messaging.competition.contract;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

public record RoomScheduleFixture(
    String contractVersion,
    UUID roomId,
    String scheduleVersion,
    RoomCompetitionType competitionType,
    RoomOrganizerType organizerType,
    RoomAccessType accessType,
    Instant recruitmentOpensAt,
    Instant participationOpensAt,
    Instant evaluationStartsAt,
    Instant participationClosesAt,
    Instant evaluationEndsAt,
    Instant finalizationDeadlineAt,
    ZoneId timeZone
) {

    public RoomScheduleFixture {
        if (!RoomContractFixtures.CONTRACT_VERSION.equals(contractVersion)) {
            throw new IllegalArgumentException("unsupported room-performance contract version: " + contractVersion);
        }
        Objects.requireNonNull(roomId, "roomId");
        scheduleVersion = requireText(scheduleVersion, "scheduleVersion");
        Objects.requireNonNull(competitionType, "competitionType");
        Objects.requireNonNull(organizerType, "organizerType");
        Objects.requireNonNull(accessType, "accessType");
        Objects.requireNonNull(recruitmentOpensAt, "recruitmentOpensAt");
        Objects.requireNonNull(participationOpensAt, "participationOpensAt");
        Objects.requireNonNull(evaluationStartsAt, "evaluationStartsAt");
        Objects.requireNonNull(participationClosesAt, "participationClosesAt");
        Objects.requireNonNull(evaluationEndsAt, "evaluationEndsAt");
        Objects.requireNonNull(finalizationDeadlineAt, "finalizationDeadlineAt");
        Objects.requireNonNull(timeZone, "timeZone");

        requireNotAfter(recruitmentOpensAt, participationOpensAt, "recruitment must open before participation");
        requireNotAfter(participationOpensAt, participationClosesAt, "participation window is reversed");
        requireBefore(evaluationStartsAt, evaluationEndsAt, "evaluation window must be non-empty");
        requireNotAfter(participationClosesAt, evaluationEndsAt, "participation closes after evaluation");
        requireNotAfter(evaluationEndsAt, finalizationDeadlineAt, "finalization precedes evaluation end");

        if (competitionType == RoomCompetitionType.LIVE_PAPER) {
            requireNotAfter(
                participationClosesAt,
                evaluationStartsAt,
                "live room participation must close before evaluation"
            );
        }
        if (competitionType == RoomCompetitionType.BACKTEST && organizerType != RoomOrganizerType.PLATFORM) {
            throw new IllegalArgumentException("backtest rooms must be organized by the platform");
        }
    }

    public boolean containsEvaluationInstant(Instant occurredAt) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        return !occurredAt.isBefore(evaluationStartsAt) && occurredAt.isBefore(evaluationEndsAt);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static void requireBefore(Instant earlier, Instant later, String message) {
        if (!earlier.isBefore(later)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireNotAfter(Instant earlier, Instant later, String message) {
        if (earlier.isAfter(later)) {
            throw new IllegalArgumentException(message);
        }
    }
}

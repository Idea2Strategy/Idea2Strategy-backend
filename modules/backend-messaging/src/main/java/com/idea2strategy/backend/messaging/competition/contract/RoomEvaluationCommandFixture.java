package com.idea2strategy.backend.messaging.competition.contract;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RoomEvaluationCommandFixture(
    String contractVersion,
    UUID commandId,
    RoomEvaluationCommandType type,
    UUID roomId,
    UUID participationId,
    UUID botId,
    UUID evaluationSegmentId,
    String scheduleVersion,
    Instant effectiveAt,
    String idempotencyKey
) {

    public RoomEvaluationCommandFixture {
        if (!RoomContractFixtures.CONTRACT_VERSION.equals(contractVersion)) {
            throw new IllegalArgumentException("unsupported room-performance contract version: " + contractVersion);
        }
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(participationId, "participationId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(evaluationSegmentId, "evaluationSegmentId");
        scheduleVersion = requireText(scheduleVersion, "scheduleVersion");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        if (!idempotencyKey.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("idempotencyKey must use sha256:<64 lowercase hex>");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

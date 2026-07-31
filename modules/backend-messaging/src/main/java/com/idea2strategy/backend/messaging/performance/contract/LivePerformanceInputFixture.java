package com.idea2strategy.backend.messaging.performance.contract;

import com.idea2strategy.backend.messaging.competition.contract.RoomContractFixtures;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record LivePerformanceInputFixture(
    String contractVersion,
    UUID eventId,
    UUID roomId,
    UUID evaluationSegmentId,
    String anonymousBotId,
    String scheduleVersion,
    PerformanceInputSource source,
    PerformanceEventType eventType,
    long sourceEventSequence,
    Instant occurredAt,
    String evidenceHash
) {

    public LivePerformanceInputFixture {
        if (!RoomContractFixtures.CONTRACT_VERSION.equals(contractVersion)) {
            throw new IllegalArgumentException("unsupported room-performance contract version: " + contractVersion);
        }
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(evaluationSegmentId, "evaluationSegmentId");
        anonymousBotId = requireText(anonymousBotId, "anonymousBotId");
        scheduleVersion = requireText(scheduleVersion, "scheduleVersion");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(eventType, "eventType");
        if (sourceEventSequence < 0) {
            throw new IllegalArgumentException("sourceEventSequence must not be negative");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        evidenceHash = requireText(evidenceHash, "evidenceHash");
    }

    public Map<String, Object> toWireMap() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("contractVersion", contractVersion);
        wire.put("eventId", eventId.toString());
        wire.put("roomId", roomId.toString());
        wire.put("evaluationSegmentId", evaluationSegmentId.toString());
        wire.put("anonymousBotId", anonymousBotId);
        wire.put("scheduleVersion", scheduleVersion);
        wire.put("source", source.name());
        wire.put("eventType", eventType.name());
        wire.put("sourceEventSequence", sourceEventSequence);
        wire.put("occurredAt", occurredAt.toString());
        wire.put("evidenceHash", evidenceHash);
        return Map.copyOf(wire);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

package com.idea2strategy.backend.application.competition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OperatorRoomView(
        RoomSummary room,
        List<RoomEvent> roomEvents,
        List<ParticipationEvent> participationEvents,
        FinalResult finalResult) {
    public OperatorRoomView {
        roomEvents = List.copyOf(roomEvents);
        participationEvents = List.copyOf(participationEvents);
    }

    public record RoomSummary(
            UUID roomId,
            String name,
            String competitionType,
            String accessType,
            String status,
            Instant createdAt,
            Instant evaluationStartsAt,
            Instant evaluationEndsAt,
            Instant endedAt,
            Instant invalidatedAt,
            String invalidationReasonCode,
            UUID scoringTemplateVersionId,
            String rulesHash) {}

    public record RoomEvent(
            int sequence,
            String eventType,
            String resultingStatus,
            String reasonCode,
            Instant occurredAt) {}

    public record ParticipationEvent(
            String anonymousAlias,
            long sequence,
            String eventType,
            String reasonCode,
            Instant occurredAt) {}

    public record FinalResult(
            UUID snapshotId,
            String status,
            Instant cutoffAt,
            String resultHash,
            UUID scoringTemplateVersionId,
            List<FinalEntry> entries) {
        public FinalResult {
            entries = List.copyOf(entries);
        }
    }

    public record FinalEntry(
            String anonymousAlias,
            Integer rank,
            boolean jointRank,
            String eligibilityStatus,
            BigDecimal score,
            String provenanceHash) {}
}

package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record FinalRoomResult(
        UUID snapshotId,
        UUID roomId,
        UUID scoringTemplateVersionId,
        Instant cutoffAt,
        String resultHash,
        Instant createdAt,
        List<FinalLeaderboardEntry> entries) {
    public FinalRoomResult {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(scoringTemplateVersionId, "scoringTemplateVersionId");
        Objects.requireNonNull(cutoffAt, "cutoffAt");
        Objects.requireNonNull(resultHash, "resultHash");
        Objects.requireNonNull(createdAt, "createdAt");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }
}

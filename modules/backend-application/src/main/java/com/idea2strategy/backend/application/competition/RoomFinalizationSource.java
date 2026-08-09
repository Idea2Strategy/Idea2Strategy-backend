package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RoomFinalizationSource(
        UUID roomId,
        Instant cutoffAt,
        ScoringTemplateCatalogRecord scoringTemplate,
        List<RoomFinalizationCandidateSource> candidates) {
    public RoomFinalizationSource {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(cutoffAt, "cutoffAt");
        Objects.requireNonNull(scoringTemplate, "scoringTemplate");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("room finalization candidates must not be empty");
        }
    }
}

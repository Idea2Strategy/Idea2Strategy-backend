package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.competition.ScoringTemplateVersion;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record FinalRoomResultCommand(
        UUID roomId,
        UUID scoringTemplateVersionId,
        Instant cutoffAt,
        ScoringTemplateVersion scoringTemplate,
        List<FinalRoomResultCandidate> candidates) {
    public FinalRoomResultCommand {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(scoringTemplateVersionId, "scoringTemplateVersionId");
        Objects.requireNonNull(cutoffAt, "cutoffAt");
        Objects.requireNonNull(scoringTemplate, "scoringTemplate");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        if (!scoringTemplateVersionId.equals(scoringTemplate.id())) {
            throw new IllegalArgumentException("locked scoring template does not match");
        }
        if (candidates.isEmpty()) throw new IllegalArgumentException("final result candidates must not be empty");
        if (new HashSet<>(candidates.stream().map(FinalRoomResultCandidate::participationId).toList()).size()
                != candidates.size()) {
            throw new IllegalArgumentException("duplicate final result participation");
        }
    }
}

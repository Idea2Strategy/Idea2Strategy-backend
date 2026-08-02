package com.idea2strategy.backend.application.competition;

import java.util.Objects;
import java.util.UUID;

public record ScoringEvidenceRequest(
        UUID participationId,
        UUID evaluationSegmentId,
        UUID performanceSnapshotId,
        UUID scoringTemplateVersionId) {

    public ScoringEvidenceRequest {
        Objects.requireNonNull(participationId, "participationId");
        Objects.requireNonNull(evaluationSegmentId, "evaluationSegmentId");
        Objects.requireNonNull(performanceSnapshotId, "performanceSnapshotId");
        Objects.requireNonNull(scoringTemplateVersionId, "scoringTemplateVersionId");
    }
}

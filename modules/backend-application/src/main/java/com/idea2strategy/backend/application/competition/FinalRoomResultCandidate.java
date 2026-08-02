package com.idea2strategy.backend.application.competition;

import java.util.Objects;
import java.util.UUID;

public record FinalRoomResultCandidate(
        UUID participationId,
        UUID performanceSnapshotId,
        OfficialScoringMetrics metrics,
        OfficialScoringEligibility eligibility,
        String provenanceHash,
        String calculationDocument) {
    public FinalRoomResultCandidate {
        Objects.requireNonNull(participationId, "participationId");
        Objects.requireNonNull(performanceSnapshotId, "performanceSnapshotId");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(eligibility, "eligibility");
        provenanceHash = requireText(provenanceHash, "provenanceHash");
        calculationDocument = requireText(calculationDocument, "calculationDocument");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}

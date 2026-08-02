package com.idea2strategy.backend.application.competition;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record FinalLeaderboardEntry(
        UUID participationId,
        UUID performanceSnapshotId,
        Integer rank,
        boolean jointRank,
        BigDecimal score,
        String eligibilityStatus,
        OfficialScoringIneligibilityReason eligibilityReason,
        String provenanceHash,
        String tieBreakDocument,
        String calculationDocument) {
    public FinalLeaderboardEntry {
        Objects.requireNonNull(participationId, "participationId");
        Objects.requireNonNull(performanceSnapshotId, "performanceSnapshotId");
        Objects.requireNonNull(eligibilityStatus, "eligibilityStatus");
        Objects.requireNonNull(provenanceHash, "provenanceHash");
        Objects.requireNonNull(tieBreakDocument, "tieBreakDocument");
        Objects.requireNonNull(calculationDocument, "calculationDocument");
        boolean eligible = "ELIGIBLE".equals(eligibilityStatus);
        if (eligible != (rank != null && score != null && eligibilityReason == null)) {
            throw new IllegalArgumentException("eligibility must agree with rank, score, and reason");
        }
        if (!eligible && (rank != null || score != null || eligibilityReason == null || jointRank)) {
            throw new IllegalArgumentException("ineligible result must remain unranked and unscored");
        }
    }
}

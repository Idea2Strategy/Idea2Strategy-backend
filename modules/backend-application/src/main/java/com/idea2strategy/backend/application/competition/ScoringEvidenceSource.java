package com.idea2strategy.backend.application.competition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ScoringEvidenceSource(
        UUID roomId,
        UUID participationId,
        UUID botId,
        UUID evaluationSegmentId,
        Instant segmentStartsAt,
        Instant segmentEndsAt,
        long startEventSequence,
        long endEventSequence,
        String initialStateHash,
        String finalStateHash,
        String sourceSetHash,
        Instant segmentFinalizedAt,
        UUID performanceSnapshotId,
        long performanceSourceEventSequence,
        Instant performanceEvaluatedAt,
        String performanceInputHash,
        String performanceCalculationRulesVersion,
        String performanceSnapshotHash,
        BigDecimal equityAmount,
        BigDecimal totalReturnPct,
        BigDecimal maxDrawdownPct,
        BigDecimal sharpeRatio,
        String performanceMetricsDocument,
        String roomRulesHash,
        String scoringParametersDocument,
        UUID lockedScoringTemplateVersionId,
        String lockedScoringTemplateRulesHash,
        UUID calculationScoringTemplateVersionId,
        String calculationScoringTemplateCode,
        String calculationScoringTemplateVersion,
        String calculationScoringTemplateRulesDocument,
        String calculationScoringTemplateRulesHash) {

    public ScoringEvidenceSource {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(participationId, "participationId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(evaluationSegmentId, "evaluationSegmentId");
        Objects.requireNonNull(segmentStartsAt, "segmentStartsAt");
        Objects.requireNonNull(segmentEndsAt, "segmentEndsAt");
        initialStateHash = requireText(initialStateHash, "initialStateHash");
        finalStateHash = requireText(finalStateHash, "finalStateHash");
        sourceSetHash = requireText(sourceSetHash, "sourceSetHash");
        Objects.requireNonNull(segmentFinalizedAt, "segmentFinalizedAt");
        Objects.requireNonNull(performanceSnapshotId, "performanceSnapshotId");
        Objects.requireNonNull(performanceEvaluatedAt, "performanceEvaluatedAt");
        performanceInputHash = requireText(performanceInputHash, "performanceInputHash");
        performanceCalculationRulesVersion = requireText(
                performanceCalculationRulesVersion, "performanceCalculationRulesVersion");
        performanceSnapshotHash = requireText(performanceSnapshotHash, "performanceSnapshotHash");
        Objects.requireNonNull(equityAmount, "equityAmount");
        Objects.requireNonNull(totalReturnPct, "totalReturnPct");
        Objects.requireNonNull(maxDrawdownPct, "maxDrawdownPct");
        performanceMetricsDocument = requireText(performanceMetricsDocument, "performanceMetricsDocument");
        roomRulesHash = requireText(roomRulesHash, "roomRulesHash");
        scoringParametersDocument = requireText(scoringParametersDocument, "scoringParametersDocument");
        Objects.requireNonNull(lockedScoringTemplateVersionId, "lockedScoringTemplateVersionId");
        lockedScoringTemplateRulesHash = requireText(
                lockedScoringTemplateRulesHash, "lockedScoringTemplateRulesHash");
        Objects.requireNonNull(calculationScoringTemplateVersionId, "calculationScoringTemplateVersionId");
        calculationScoringTemplateCode = requireText(
                calculationScoringTemplateCode, "calculationScoringTemplateCode");
        calculationScoringTemplateVersion = requireText(
                calculationScoringTemplateVersion, "calculationScoringTemplateVersion");
        calculationScoringTemplateRulesDocument = requireText(
                calculationScoringTemplateRulesDocument, "calculationScoringTemplateRulesDocument");
        calculationScoringTemplateRulesHash = requireText(
                calculationScoringTemplateRulesHash, "calculationScoringTemplateRulesHash");
        if (!segmentStartsAt.isBefore(segmentEndsAt)) {
            throw new IllegalArgumentException("evaluation segment must be non-empty");
        }
        if (startEventSequence < 0 || endEventSequence < startEventSequence) {
            throw new IllegalArgumentException("evaluation segment event sequence is invalid");
        }
        if (performanceSourceEventSequence != endEventSequence) {
            throw new IllegalArgumentException("performance snapshot must use the finalized segment event sequence");
        }
        if (!performanceEvaluatedAt.equals(segmentEndsAt)) {
            throw new IllegalArgumentException("performance snapshot must use the official segment cutoff");
        }
        if (segmentFinalizedAt.isBefore(segmentEndsAt)) {
            throw new IllegalArgumentException("evaluation segment cannot finalize before its cutoff");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}

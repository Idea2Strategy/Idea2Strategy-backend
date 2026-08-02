package com.idea2strategy.backend.application.competition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class ScoringEvidenceService {
    public static final String PROVENANCE_VERSION = "live-scoring-evidence.v1";
    private final ScoringEvidencePort port;

    public ScoringEvidenceService(ScoringEvidencePort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    public ScoringEvidenceBundle prepare(ScoringEvidenceRequest request) {
        var source = port.load(Objects.requireNonNull(request, "request"));
        return new ScoringEvidenceBundle(PROVENANCE_VERSION, source, provenanceHash(source));
    }

    private static String provenanceHash(ScoringEvidenceSource source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest(digest, "provenanceVersion", PROVENANCE_VERSION);
            digest(digest, "roomId", source.roomId());
            digest(digest, "participationId", source.participationId());
            digest(digest, "botId", source.botId());
            digest(digest, "evaluationSegmentId", source.evaluationSegmentId());
            digest(digest, "segmentStartsAt", source.segmentStartsAt());
            digest(digest, "segmentEndsAt", source.segmentEndsAt());
            digest(digest, "startEventSequence", source.startEventSequence());
            digest(digest, "endEventSequence", source.endEventSequence());
            digest(digest, "initialStateHash", source.initialStateHash());
            digest(digest, "finalStateHash", source.finalStateHash());
            digest(digest, "sourceSetHash", source.sourceSetHash());
            digest(digest, "segmentFinalizedAt", source.segmentFinalizedAt());
            digest(digest, "performanceSnapshotId", source.performanceSnapshotId());
            digest(digest, "performanceSourceEventSequence", source.performanceSourceEventSequence());
            digest(digest, "performanceEvaluatedAt", source.performanceEvaluatedAt());
            digest(digest, "performanceInputHash", source.performanceInputHash());
            digest(digest, "performanceCalculationRulesVersion", source.performanceCalculationRulesVersion());
            digest(digest, "performanceSnapshotHash", source.performanceSnapshotHash());
            digest(digest, "equityAmount", source.equityAmount().toPlainString());
            digest(digest, "totalReturnPct", source.totalReturnPct().toPlainString());
            digest(digest, "maxDrawdownPct", source.maxDrawdownPct().toPlainString());
            digest(digest, "sharpeRatio", source.sharpeRatio() == null ? "<null>" : source.sharpeRatio().toPlainString());
            digest(digest, "performanceMetricsDocument", source.performanceMetricsDocument());
            digest(digest, "roomRulesHash", source.roomRulesHash());
            digest(digest, "scoringParametersDocument", source.scoringParametersDocument());
            digest(digest, "lockedScoringTemplateVersionId", source.lockedScoringTemplateVersionId());
            digest(digest, "lockedScoringTemplateRulesHash", source.lockedScoringTemplateRulesHash());
            digest(digest, "calculationScoringTemplateVersionId", source.calculationScoringTemplateVersionId());
            digest(digest, "calculationScoringTemplateCode", source.calculationScoringTemplateCode());
            digest(digest, "calculationScoringTemplateVersion", source.calculationScoringTemplateVersion());
            digest(digest, "calculationScoringTemplateRulesDocument", source.calculationScoringTemplateRulesDocument());
            digest(digest, "calculationScoringTemplateRulesHash", source.calculationScoringTemplateRulesHash());
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void digest(MessageDigest digest, String name, Object value) {
        updateLengthPrefixed(digest, name);
        String canonical = switch (value) {
            case Instant instant -> instant.toString();
            case UUID uuid -> uuid.toString();
            default -> String.valueOf(value);
        };
        updateLengthPrefixed(digest, canonical);
    }

    private static void updateLengthPrefixed(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(new byte[] {
                (byte) (bytes.length >>> 24),
                (byte) (bytes.length >>> 16),
                (byte) (bytes.length >>> 8),
                (byte) bytes.length
        });
        digest.update(bytes);
    }
}

package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScoringEvidenceServiceTest {
    private static final UUID CALCULATION_TEMPLATE_ID = id(9);

    @Test
    void producesTheSameLengthPrefixedHashForTheSameImmutableSources() {
        var source = source(CALCULATION_TEMPLATE_ID, "calculation-rules-hash-v1");
        var service = new ScoringEvidenceService(request -> source);
        var request = request(CALCULATION_TEMPLATE_ID);

        var first = service.prepare(request);
        var second = service.prepare(request);

        assertThat(first.provenanceVersion()).isEqualTo("live-scoring-evidence.v1");
        assertThat(first.provenanceHash()).isEqualTo(second.provenanceHash());
        assertThat(first.provenanceHash())
                .isEqualTo("sha256:8decc013926f4d5b0edea79d242ac99d520a936b7b9077e048471748a39d4e0d");
        assertThat(first.provenanceHash()).matches("sha256:[0-9a-f]{64}");
        assertThat(first.source()).isSameAs(source);
    }

    @Test
    void aNewCalculationTemplateCreatesSeparateProvenanceWithoutChangingTheLockedRoomTemplate() {
        var original = new ScoringEvidenceService(request -> source(
                        request.scoringTemplateVersionId(), "calculation-rules-hash-v1"))
                .prepare(request(CALCULATION_TEMPLATE_ID));
        var recalculationTemplateId = id(10);
        var recalculated = new ScoringEvidenceService(request -> source(
                        request.scoringTemplateVersionId(), "calculation-rules-hash-v2"))
                .prepare(request(recalculationTemplateId));

        assertThat(recalculated.provenanceHash()).isNotEqualTo(original.provenanceHash());
        assertThat(recalculated.source().lockedScoringTemplateVersionId())
                .isEqualTo(original.source().lockedScoringTemplateVersionId());
        assertThat(recalculated.source().performanceSnapshotId())
                .isEqualTo(original.source().performanceSnapshotId());
    }

    @Test
    void performanceMetricChangesCreateDifferentProvenanceEvenWhenSnapshotIdentifiersAreUnchanged() {
        var original = new ScoringEvidenceService(request -> source(
                        request.scoringTemplateVersionId(), "calculation-rules-hash-v1", "10"))
                .prepare(request(CALCULATION_TEMPLATE_ID));
        var changedMetric = new ScoringEvidenceService(request -> source(
                        request.scoringTemplateVersionId(), "calculation-rules-hash-v1", "10.00000001"))
                .prepare(request(CALCULATION_TEMPLATE_ID));

        assertThat(changedMetric.source().performanceSnapshotId())
                .isEqualTo(original.source().performanceSnapshotId());
        assertThat(changedMetric.provenanceHash()).isNotEqualTo(original.provenanceHash());
    }

    @Test
    void rejectsAPerformanceSnapshotThatDoesNotMatchTheFinalizedSegmentBoundary() {
        assertThatThrownBy(() -> new ScoringEvidenceSource(
                        id(1), id(2), id(3), id(4),
                        instant(0), instant(600), 10, 20,
                        "initial", "final", "sources", instant(601),
                        id(5), 19, instant(600), "input", "performance-v1", "snapshot",
                        amount("110000"), amount("10"), amount("4"), amount("1.2"), "{}",
                        "room-rules", "{}", id(8), "locked-rules", id(9),
                        "TOTAL_RETURN", "1", "{}", "calculation-rules"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finalized segment event sequence");
    }

    private static ScoringEvidenceRequest request(UUID templateId) {
        return new ScoringEvidenceRequest(id(2), id(4), id(5), templateId);
    }

    private static ScoringEvidenceSource source(UUID calculationTemplateId, String calculationRulesHash) {
        return source(calculationTemplateId, calculationRulesHash, "10");
    }

    private static ScoringEvidenceSource source(
            UUID calculationTemplateId, String calculationRulesHash, String totalReturnPct) {
        return new ScoringEvidenceSource(
                id(1), id(2), id(3), id(4),
                instant(0), instant(600), 10, 20,
                "initial-state-hash", "final-state-hash", "source-set-hash", instant(601),
                id(5), 20, instant(600), "performance-input-hash", "performance-v1", "snapshot-hash",
                amount("110000"), amount(totalReturnPct), amount("4"), amount("1.2"), "{\"turnoverPct\": 12}",
                "room-rules-hash", "{\"minimumTrades\": 1}", id(8), "locked-rules-hash",
                calculationTemplateId, "TOTAL_RETURN", calculationTemplateId.equals(CALCULATION_TEMPLATE_ID) ? "1" : "2",
                "{\"kind\": \"SINGLE\"}", calculationRulesHash);
    }

    private static Instant instant(long seconds) {
        return Instant.parse("2026-08-02T05:00:00Z").plusSeconds(seconds);
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("b6000000-0000-4000-8000-" + String.format("%012d", suffix));
    }
}

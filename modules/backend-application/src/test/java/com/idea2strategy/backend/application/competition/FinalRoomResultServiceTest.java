package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.domain.competition.ScoringComponent;
import com.idea2strategy.backend.domain.competition.ScoringDirection;
import com.idea2strategy.backend.domain.competition.ScoringMetric;
import com.idea2strategy.backend.domain.competition.ScoringTemplateKind;
import com.idea2strategy.backend.domain.competition.ScoringTemplateVersion;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinalRoomResultServiceTest {
    private static final Instant CUTOFF = Instant.parse("2026-08-02T20:00:00Z");
    private static final Instant CREATED = CUTOFF.plusSeconds(10);
    private final CapturingPort port = new CapturingPort();
    private final FinalRoomResultService service = new FinalRoomResultService(
            port, Clock.fixed(CREATED, ZoneOffset.UTC));

    @Test
    void freezesCompetitionRanksAndKeepsIneligibleResultsUnranked() {
        var command = new FinalRoomResultCommand(
                id(1), id(2), CUTOFF, singleReturn(), List.of(
                        eligible(11, "10"),
                        eligible(12, "10"),
                        ineligible(13, OfficialScoringIneligibilityReason.COVERAGE_BELOW_MINIMUM)));

        var outcome = service.finalize(command);

        assertThat(outcome).isEqualTo(FinalRoomResultWriteDecision.CREATED);
        assertThat(port.saved.entries()).extracting(FinalLeaderboardEntry::rank)
                .containsExactly(1, 1, null);
        assertThat(port.saved.entries()).extracting(FinalLeaderboardEntry::score)
                .containsExactly(decimal("10.00000000"), decimal("10.00000000"), null);
        assertThat(port.saved.entries().get(2).eligibilityReason())
                .isEqualTo(OfficialScoringIneligibilityReason.COVERAGE_BELOW_MINIMUM);
    }

    @Test
    void producesTheSameSnapshotAndResultHashForAnIdenticalRetry() {
        var command = new FinalRoomResultCommand(
                id(1), id(2), CUTOFF, singleReturn(), List.of(eligible(11, "5")));

        service.finalize(command);
        var first = port.saved;
        service.finalize(command);

        assertThat(port.saved.snapshotId()).isEqualTo(first.snapshotId());
        assertThat(port.saved.resultHash()).isEqualTo(first.resultHash());
    }

    @Test
    void keepsAnOtherwiseEligibleCandidateUnrankedWhenTheTemplateMetricIsUnavailable() {
        var candidate = new FinalRoomResultCandidate(
                id(11), id(111), new OfficialScoringMetrics(decimal("10"), decimal("5"), null),
                new OfficialScoringEligibility(decimal("1"), 0, 0, true, List.of()),
                "sha256:provenance-11", "{\"source\":\"official\"}");
        var sharpeTemplate = new ScoringTemplateVersion(
                id(3), "SINGLE_SHARPE_V1", "1", ScoringTemplateKind.SINGLE,
                OfficialScoringCalculator.CALCULATION_RULES_VERSION,
                List.of(new ScoringComponent(
                        ScoringMetric.SHARPE_RATIO, ScoringDirection.HIGHER_IS_BETTER, BigDecimal.ONE)),
                List.of(), "sha256:rules", CUTOFF.minusSeconds(100), null);

        service.finalize(new FinalRoomResultCommand(id(1), id(3), CUTOFF, sharpeTemplate, List.of(candidate)));

        assertThat(port.saved.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.rank()).isNull();
            assertThat(entry.score()).isNull();
            assertThat(entry.eligibilityReason())
                    .isEqualTo(OfficialScoringIneligibilityReason.REQUIRED_METRIC_UNAVAILABLE);
        });
    }

    private static FinalRoomResultCandidate eligible(int suffix, String totalReturn) {
        return new FinalRoomResultCandidate(
                id(suffix), id(100 + suffix),
                new OfficialScoringMetrics(decimal(totalReturn), decimal("5"), decimal("1")),
                new OfficialScoringEligibility(decimal("1"), 0, 0, true, List.of()),
                "sha256:provenance-" + suffix, "{\"source\":\"official\"}");
    }

    private static FinalRoomResultCandidate ineligible(
            int suffix, OfficialScoringIneligibilityReason reason) {
        return new FinalRoomResultCandidate(
                id(suffix), id(100 + suffix),
                new OfficialScoringMetrics(decimal("100"), decimal("1"), decimal("3")),
                new OfficialScoringEligibility(decimal("0.69"), 0, 0, false, List.of(reason)),
                "sha256:provenance-" + suffix, "{\"source\":\"official\"}");
    }

    private static ScoringTemplateVersion singleReturn() {
        return new ScoringTemplateVersion(
                id(2), "SINGLE_TOTAL_RETURN_V1", "1", ScoringTemplateKind.SINGLE,
                OfficialScoringCalculator.CALCULATION_RULES_VERSION,
                List.of(new ScoringComponent(
                        ScoringMetric.TOTAL_RETURN, ScoringDirection.HIGHER_IS_BETTER, BigDecimal.ONE)),
                List.of(), "sha256:rules", CUTOFF.minusSeconds(100), null);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("e2800000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    private static final class CapturingPort implements FinalRoomResultPort {
        private FinalRoomResult saved;

        @Override
        public FinalRoomResultWriteDecision save(FinalRoomResult result) {
            saved = result;
            return FinalRoomResultWriteDecision.CREATED;
        }
    }
}

package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.domain.competition.ScoringComponent;
import com.idea2strategy.backend.domain.competition.ScoringDirection;
import com.idea2strategy.backend.domain.competition.ScoringMetric;
import com.idea2strategy.backend.domain.competition.ScoringTemplateKind;
import com.idea2strategy.backend.domain.competition.ScoringTemplateVersion;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfficialScoringCalculatorTest {
    private final OfficialScoringCalculator calculator = new OfficialScoringCalculator();

    @Test
    void preservesTheRawMetricForSingleAndUsesFixedCompositeNormalization() {
        var metrics = new OfficialScoringMetrics(decimal("50"), decimal("25"), decimal("1.5"));

        assertThat(calculator.score(single(ScoringMetric.TOTAL_RETURN), metrics))
                .isEqualByComparingTo("50.00000000");
        assertThat(calculator.score(composite(), metrics))
                .isEqualByComparingTo("75.00000000");
    }

    @Test
    void calculatesReturnAndDrawdownWithThePublishedNumericScale() {
        var observations = List.of(
                observation("normal-1", 0, "120"),
                observation("normal-1", 60, "90"),
                observation("normal-1", 120, "110"));

        var metrics = calculator.metrics(decimal("100"), observations);

        assertThat(metrics.totalReturnPct()).isEqualByComparingTo("10.00000000");
        assertThat(metrics.maxDrawdownPct()).isEqualByComparingTo("25.00000000");
        assertThat(metrics.sharpeRatio()).isNull();
    }

    @Test
    void neverBuildsASharpeReturnPairAcrossAnOutageGap() {
        var observations = new ArrayList<OfficialEquityObservation>();
        for (int index = 0; index <= 15; index++) {
            observations.add(observation("normal-1", index * 60L, Integer.toString(100 + index)));
        }
        for (int index = 16; index <= 31; index++) {
            observations.add(observation("normal-2", 3_600 + index * 60L, Integer.toString(100 + index)));
        }

        var metrics = calculator.metrics(decimal("100"), observations);

        assertThat(metrics.sharpeRatio()).isNotNull();
        assertThat(calculator.validSharpePairCount(observations)).isEqualTo(30);
    }

    @Test
    void pinsThePublishedStrictMathDecimalConversionVectors() {
        assertThat(Double.doubleToRawLongBits(StrictMath.log(2.0d)))
                .isEqualTo(0x3fe62e42fefa39efL);
        assertThat(Double.doubleToRawLongBits(StrictMath.log(0.5d)))
                .isEqualTo(0xbfe62e42fefa39efL);
        assertThat(Double.doubleToRawLongBits(StrictMath.sqrt(2.0d)))
                .isEqualTo(0x3ff6a09e667f3bcdL);
        assertThat(OfficialScoringCalculator.strictLog(decimal("2.0000000000000000")))
                .isEqualByComparingTo("0.6931471805599453");
        assertThat(OfficialScoringCalculator.strictLog(decimal("0.5000000000000000")))
                .isEqualByComparingTo("-0.6931471805599453");
        assertThat(OfficialScoringCalculator.strictSqrt(decimal("2.0000000000000000")))
                .isEqualByComparingTo("1.4142135623730951");
    }

    @Test
    void rejectsATemplateWhenItsRequiredMetricIsUnavailable() {
        var metrics = new OfficialScoringMetrics(decimal("10"), decimal("5"), null);

        assertThatThrownBy(() -> calculator.score(single(ScoringMetric.SHARPE_RATIO), metrics))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHARPE_RATIO");
        assertThatThrownBy(() -> calculator.score(composite(), metrics))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHARPE_RATIO");
    }

    @Test
    void rejectsAnUnpublishedTemplateCodeEvenWhenItsShapeLooksValid() {
        var unpublished = template("CUSTOM_RETURN", ScoringTemplateKind.SINGLE,
                List.of(new ScoringComponent(
                        ScoringMetric.TOTAL_RETURN, ScoringDirection.HIGHER_IS_BETTER, BigDecimal.ONE)));

        assertThatThrownBy(() -> calculator.score(
                        unpublished, new OfficialScoringMetrics(decimal("1"), decimal("2"), decimal("3"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported official single template");
    }

    @Test
    void appliesCoverageAndProportionallyAdjustedEligibilityWithoutInventingAScore() {
        var belowCoverage = calculator.eligibility(699, 1_000, 700, 700, 7, 7, true);
        var exactCoverage = calculator.eligibility(700, 1_000, 700, 700, 7, 7, true);
        var adjustedThresholds = calculator.eligibility(700, 1_000, 69, 100, 6, 10, true);

        assertThat(belowCoverage.eligible()).isFalse();
        assertThat(belowCoverage.reasons()).containsExactly(OfficialScoringIneligibilityReason.COVERAGE_BELOW_MINIMUM);
        assertThat(exactCoverage.eligible()).isTrue();
        assertThat(adjustedThresholds.requiredOperationSeconds()).isEqualTo(70);
        assertThat(adjustedThresholds.requiredFillCount()).isEqualTo(7);
        assertThat(adjustedThresholds.reasons()).containsExactly(
                OfficialScoringIneligibilityReason.MINIMUM_OPERATION_NOT_MET,
                OfficialScoringIneligibilityReason.MINIMUM_FILL_COUNT_NOT_MET);
    }

    @Test
    void assignsCompetitionRankAndUsesParticipationOnlyForStableDisplayOrder() {
        var first = result(1, "80", "10", "2", "5");
        var tied = result(2, "80", "10", "2", "5");
        var third = result(3, "70", "20", "1", "10");

        var ranked = new OfficialScoringRanker().rank(List.of(third, tied, first));

        assertThat(ranked).extracting(OfficialScoringRank::rank).containsExactly(1, 1, 3);
        assertThat(ranked).extracting(rank -> rank.result().participationId())
                .containsExactly(first.participationId(), tied.participationId(), third.participationId());
    }

    private static OfficialScoringResult result(int suffix, String score, String totalReturn, String sharpe, String mdd) {
        return new OfficialScoringResult(
                id(suffix), decimal(score), ScoringDirection.HIGHER_IS_BETTER,
                new OfficialScoringMetrics(decimal(totalReturn), decimal(mdd), decimal(sharpe)));
    }

    private static OfficialEquityObservation observation(String segment, long seconds, String equity) {
        return new OfficialEquityObservation(
                segment, Instant.parse("2026-08-01T13:30:00Z").plusSeconds(seconds), decimal(equity));
    }

    private static ScoringTemplateVersion single(ScoringMetric metric) {
        String code = switch (metric) {
            case TOTAL_RETURN -> "SINGLE_TOTAL_RETURN_V1";
            case SHARPE_RATIO -> "SINGLE_SHARPE_V1";
            case MAX_DRAWDOWN -> "SINGLE_MAX_DRAWDOWN_V1";
        };
        return template(code, ScoringTemplateKind.SINGLE,
                List.of(new ScoringComponent(metric, metric.direction(), BigDecimal.ONE)));
    }

    private static ScoringTemplateVersion composite() {
        return template("COMPOSITE_BALANCED_V1", ScoringTemplateKind.COMPOSITE, List.of(
                new ScoringComponent(ScoringMetric.TOTAL_RETURN, ScoringDirection.HIGHER_IS_BETTER, decimal("0.50")),
                new ScoringComponent(ScoringMetric.SHARPE_RATIO, ScoringDirection.HIGHER_IS_BETTER, decimal("0.30")),
                new ScoringComponent(ScoringMetric.MAX_DRAWDOWN, ScoringDirection.LOWER_IS_BETTER, decimal("0.20"))));
    }

    private static ScoringTemplateVersion template(
            String code, ScoringTemplateKind kind, List<ScoringComponent> components) {
        return new ScoringTemplateVersion(
                id(99), code, "1", kind, OfficialScoringCalculator.CALCULATION_RULES_VERSION,
                components, List.of(), "sha256:rules", Instant.parse("2026-08-01T00:00:00Z"), null);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("e1800000-0000-4000-8000-" + String.format("%012d", suffix));
    }
}

package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.competition.ScoringMetric;
import com.idea2strategy.backend.domain.competition.ScoringTemplateKind;
import com.idea2strategy.backend.domain.competition.ScoringTemplateVersion;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class OfficialScoringCalculator {
    public static final String CALCULATION_RULES_VERSION = "official-room-scoring.v1";
    private static final int INPUT_SCALE = 8;
    private static final int INTERMEDIATE_SCALE = 16;
    private static final int SCORE_SCALE = 8;
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal YEAR_SECONDS = BigDecimal.valueOf(31_557_600);
    private static final BigDecimal MINIMUM_COVERAGE = new BigDecimal("0.70");

    public OfficialScoringMetrics metrics(
            BigDecimal initialCapital,
            List<OfficialEquityObservation> inputObservations) {
        BigDecimal initial = normalizedInput(initialCapital);
        if (initial.signum() <= 0) {
            throw new IllegalArgumentException("initialCapital must be positive");
        }
        List<OfficialEquityObservation> observations = validated(inputObservations);
        BigDecimal finalEquity = normalizedInput(observations.getLast().equityAmount());
        BigDecimal totalReturn = finalEquity.divide(initial, INTERMEDIATE_SCALE, RoundingMode.HALF_EVEN)
                .subtract(BigDecimal.ONE, MC)
                .multiply(ONE_HUNDRED, MC)
                .setScale(INPUT_SCALE, RoundingMode.HALF_EVEN);
        BigDecimal maxDrawdown = maximumDrawdown(initial, observations);
        BigDecimal sharpe = sharpe(observations);
        return new OfficialScoringMetrics(totalReturn, maxDrawdown, sharpe);
    }

    public BigDecimal score(ScoringTemplateVersion template, OfficialScoringMetrics metrics) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(metrics, "metrics");
        if (!CALCULATION_RULES_VERSION.equals(template.calculationRulesVersion())) {
            throw new IllegalArgumentException("unsupported calculation rules version");
        }
        if (!template.adjustmentDefinitions().isEmpty()) {
            throw new IllegalArgumentException("official-room-scoring.v1 does not allow adjustments");
        }
        if (template.kind() == ScoringTemplateKind.SINGLE) {
            requireOfficialSingle(template);
            return metric(metrics, template.components().getFirst().metric())
                    .setScale(SCORE_SCALE, RoundingMode.HALF_EVEN);
        }
        requireBalancedComposite(template);
        Map<ScoringMetric, BigDecimal> normalized = new EnumMap<>(ScoringMetric.class);
        normalized.put(ScoringMetric.TOTAL_RETURN,
                clamp(metrics.totalReturnPct(), decimal("-100"), decimal("100"))
                        .add(ONE_HUNDRED, MC)
                        .divide(decimal("200"), INTERMEDIATE_SCALE, RoundingMode.HALF_EVEN));
        if (metrics.sharpeRatio() == null) {
            throw new IllegalArgumentException("required scoring metric is unavailable: SHARPE_RATIO");
        }
        normalized.put(ScoringMetric.SHARPE_RATIO,
                clamp(metrics.sharpeRatio(), decimal("-3"), decimal("3"))
                        .add(decimal("3"), MC)
                        .divide(decimal("6"), INTERMEDIATE_SCALE, RoundingMode.HALF_EVEN));
        normalized.put(ScoringMetric.MAX_DRAWDOWN,
                ONE_HUNDRED.subtract(
                                clamp(metrics.maxDrawdownPct(), BigDecimal.ZERO, ONE_HUNDRED), MC)
                        .divide(ONE_HUNDRED, INTERMEDIATE_SCALE, RoundingMode.HALF_EVEN));
        BigDecimal weighted = template.components().stream()
                .map(component -> normalized.get(component.metric())
                        .multiply(component.coefficient(), MC)
                        .setScale(INTERMEDIATE_SCALE, RoundingMode.HALF_EVEN))
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        return weighted.multiply(ONE_HUNDRED, MC).setScale(SCORE_SCALE, RoundingMode.HALF_EVEN);
    }

    public OfficialScoringEligibility eligibility(
            long normalEvaluationSeconds,
            long scheduledEvaluationSeconds,
            long actualOperationSeconds,
            long baseRequiredOperationSeconds,
            int actualFillCount,
            int baseRequiredFillCount,
            boolean evidenceRecoverable) {
        if (normalEvaluationSeconds < 0 || scheduledEvaluationSeconds <= 0
                || normalEvaluationSeconds > scheduledEvaluationSeconds
                || actualOperationSeconds < 0 || baseRequiredOperationSeconds < 0
                || actualFillCount < 0 || baseRequiredFillCount < 0) {
            throw new IllegalArgumentException("coverage and eligibility evidence is invalid");
        }
        BigDecimal coverage = BigDecimal.valueOf(normalEvaluationSeconds)
                .divide(BigDecimal.valueOf(scheduledEvaluationSeconds), INTERMEDIATE_SCALE, RoundingMode.HALF_EVEN);
        long requiredOperation = adjusted(baseRequiredOperationSeconds, coverage).longValueExact();
        int requiredFills = adjusted(baseRequiredFillCount, coverage).intValueExact();
        List<OfficialScoringIneligibilityReason> reasons = new ArrayList<>();
        if (coverage.compareTo(MINIMUM_COVERAGE) < 0) {
            reasons.add(OfficialScoringIneligibilityReason.COVERAGE_BELOW_MINIMUM);
        }
        if (actualOperationSeconds < requiredOperation) {
            reasons.add(OfficialScoringIneligibilityReason.MINIMUM_OPERATION_NOT_MET);
        }
        if (actualFillCount < requiredFills) {
            reasons.add(OfficialScoringIneligibilityReason.MINIMUM_FILL_COUNT_NOT_MET);
        }
        if (!evidenceRecoverable) {
            reasons.add(OfficialScoringIneligibilityReason.NORMAL_EVIDENCE_UNRECOVERABLE);
        }
        return new OfficialScoringEligibility(
                coverage, requiredOperation, requiredFills, reasons.isEmpty(), reasons);
    }

    public int validSharpePairCount(List<OfficialEquityObservation> observations) {
        List<OfficialEquityObservation> validated = validated(observations);
        int count = 0;
        for (int index = 1; index < validated.size(); index++) {
            OfficialEquityObservation previous = validated.get(index - 1);
            OfficialEquityObservation current = validated.get(index);
            if (sameNormalSegment(previous, current) && positivePair(previous, current)) {
                count++;
            }
        }
        return count;
    }

    static BigDecimal strictLog(BigDecimal operand) {
        return strictUnary(operand, true);
    }

    static BigDecimal strictSqrt(BigDecimal operand) {
        return strictUnary(operand, false);
    }

    private static BigDecimal sharpe(List<OfficialEquityObservation> observations) {
        List<ReturnPair> pairs = new ArrayList<>();
        for (int index = 1; index < observations.size(); index++) {
            OfficialEquityObservation previous = observations.get(index - 1);
            OfficialEquityObservation current = observations.get(index);
            if (!sameNormalSegment(previous, current) || !positivePair(previous, current)) {
                continue;
            }
            long elapsedNanos = Duration.between(previous.observedAt(), current.observedAt()).toNanos();
            BigDecimal elapsedSeconds = BigDecimal.valueOf(elapsedNanos, 9);
            BigDecimal dt = elapsedSeconds.divide(YEAR_SECONDS, INTERMEDIATE_SCALE, RoundingMode.HALF_EVEN);
            BigDecimal ratio = normalizedInput(current.equityAmount())
                    .divide(normalizedInput(previous.equityAmount()), INTERMEDIATE_SCALE, RoundingMode.HALF_EVEN);
            pairs.add(new ReturnPair(strictLog(ratio), dt));
        }
        if (pairs.size() < 30) {
            return null;
        }
        BigDecimal totalTime = pairs.stream().map(ReturnPair::dt)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        if (totalTime.signum() <= 0) {
            return null;
        }
        BigDecimal totalLogReturn = pairs.stream().map(ReturnPair::logReturn)
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
        BigDecimal mu = totalLogReturn.divide(totalTime, INTERMEDIATE_SCALE, RoundingMode.HALF_EVEN);
        BigDecimal varianceSum = BigDecimal.ZERO;
        for (ReturnPair pair : pairs) {
            BigDecimal expected = mu.multiply(pair.dt(), MC)
                    .setScale(INTERMEDIATE_SCALE, RoundingMode.HALF_EVEN);
            BigDecimal difference = pair.logReturn().subtract(expected, MC);
            BigDecimal term = difference.multiply(difference, MC)
                    .divide(pair.dt(), INTERMEDIATE_SCALE, RoundingMode.HALF_EVEN);
            varianceSum = varianceSum.add(term, MC);
        }
        BigDecimal variance = varianceSum.divide(
                BigDecimal.valueOf(pairs.size() - 1L), INTERMEDIATE_SCALE, RoundingMode.HALF_EVEN);
        if (variance.signum() <= 0) {
            return null;
        }
        BigDecimal standardDeviation = strictSqrt(variance);
        if (standardDeviation.signum() <= 0) {
            return null;
        }
        return mu.divide(standardDeviation, INTERMEDIATE_SCALE, RoundingMode.HALF_EVEN)
                .setScale(INPUT_SCALE, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal maximumDrawdown(
            BigDecimal initial,
            List<OfficialEquityObservation> observations) {
        BigDecimal peak = initial;
        BigDecimal maximum = BigDecimal.ZERO.setScale(INPUT_SCALE, RoundingMode.HALF_EVEN);
        for (OfficialEquityObservation observation : observations) {
            BigDecimal equity = normalizedInput(observation.equityAmount());
            if (equity.compareTo(peak) > 0) {
                peak = equity;
                continue;
            }
            BigDecimal drawdown = peak.subtract(equity, MC)
                    .divide(peak, INTERMEDIATE_SCALE, RoundingMode.HALF_EVEN)
                    .multiply(ONE_HUNDRED, MC)
                    .setScale(INPUT_SCALE, RoundingMode.HALF_EVEN);
            maximum = maximum.max(drawdown);
        }
        return maximum;
    }

    private static List<OfficialEquityObservation> validated(List<OfficialEquityObservation> observations) {
        List<OfficialEquityObservation> copy = List.copyOf(Objects.requireNonNull(observations, "observations"));
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("official equity observations must not be empty");
        }
        for (int index = 1; index < copy.size(); index++) {
            if (!copy.get(index).observedAt().isAfter(copy.get(index - 1).observedAt())) {
                throw new IllegalArgumentException("official equity observations must be strictly time ordered");
            }
        }
        return copy;
    }

    private static boolean sameNormalSegment(OfficialEquityObservation left, OfficialEquityObservation right) {
        return left.normalSegmentId().equals(right.normalSegmentId());
    }

    private static boolean positivePair(OfficialEquityObservation left, OfficialEquityObservation right) {
        return left.equityAmount().signum() > 0 && right.equityAmount().signum() > 0;
    }

    private static BigDecimal metric(OfficialScoringMetrics metrics, ScoringMetric metric) {
        BigDecimal value = switch (metric) {
            case TOTAL_RETURN -> metrics.totalReturnPct();
            case MAX_DRAWDOWN -> metrics.maxDrawdownPct();
            case SHARPE_RATIO -> metrics.sharpeRatio();
        };
        if (value == null) {
            throw new IllegalArgumentException("required scoring metric is unavailable: " + metric);
        }
        return value;
    }

    private static void requireBalancedComposite(ScoringTemplateVersion template) {
        if (!"COMPOSITE_BALANCED_V1".equals(template.templateCode()) || template.components().size() != 3) {
            throw new IllegalArgumentException("unsupported official composite template");
        }
        Map<ScoringMetric, BigDecimal> expected = Map.of(
                ScoringMetric.TOTAL_RETURN, decimal("0.50"),
                ScoringMetric.SHARPE_RATIO, decimal("0.30"),
                ScoringMetric.MAX_DRAWDOWN, decimal("0.20"));
        boolean matches = template.components().stream().allMatch(component ->
                expected.containsKey(component.metric())
                        && expected.get(component.metric()).compareTo(component.coefficient()) == 0);
        if (!matches) {
            throw new IllegalArgumentException("composite template does not match official v1 weights");
        }
    }

    private static void requireOfficialSingle(ScoringTemplateVersion template) {
        ScoringMetric metric = template.components().getFirst().metric();
        String expectedCode = switch (metric) {
            case TOTAL_RETURN -> "SINGLE_TOTAL_RETURN_V1";
            case SHARPE_RATIO -> "SINGLE_SHARPE_V1";
            case MAX_DRAWDOWN -> "SINGLE_MAX_DRAWDOWN_V1";
        };
        if (!expectedCode.equals(template.templateCode())) {
            throw new IllegalArgumentException("unsupported official single template");
        }
    }

    private static BigDecimal adjusted(long baseRequirement, BigDecimal coverage) {
        if (baseRequirement == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(baseRequirement).multiply(coverage, MC)
                .setScale(0, RoundingMode.CEILING)
                .max(BigDecimal.ONE);
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal minimum, BigDecimal maximum) {
        return value.max(minimum).min(maximum);
    }

    private static BigDecimal strictUnary(BigDecimal operand, boolean logarithm) {
        double input = Objects.requireNonNull(operand, "operand")
                .setScale(INTERMEDIATE_SCALE, RoundingMode.HALF_EVEN)
                .doubleValue();
        if (!Double.isFinite(input)) {
            throw new IllegalArgumentException("numeric operand is not finite");
        }
        double result = logarithm ? StrictMath.log(input) : StrictMath.sqrt(input);
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException("numeric result is not finite");
        }
        return BigDecimal.valueOf(result).setScale(INTERMEDIATE_SCALE, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal normalizedInput(BigDecimal value) {
        return Objects.requireNonNull(value, "value").setScale(INPUT_SCALE, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private record ReturnPair(BigDecimal logReturn, BigDecimal dt) {}
}

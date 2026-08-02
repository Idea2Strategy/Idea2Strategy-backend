package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.competition.ScoringDirection;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class OfficialScoringRanker {
    public List<OfficialScoringRank> rank(List<OfficialScoringResult> input) {
        var results = new ArrayList<>(List.copyOf(input));
        if (results.isEmpty()) {
            return List.of();
        }
        ScoringDirection direction = results.getFirst().scoreDirection();
        if (results.stream().anyMatch(result -> result.scoreDirection() != direction)) {
            throw new IllegalArgumentException("all ranked results must use the same score direction");
        }
        results.sort(comparator(direction));
        List<OfficialScoringRank> ranked = new ArrayList<>(results.size());
        int currentRank = 1;
        for (int index = 0; index < results.size(); index++) {
            if (index > 0 && !scoringTie(results.get(index - 1), results.get(index))) {
                currentRank = index + 1;
            }
            ranked.add(new OfficialScoringRank(currentRank, results.get(index)));
        }
        return List.copyOf(ranked);
    }

    private static Comparator<OfficialScoringResult> comparator(ScoringDirection direction) {
        return (left, right) -> {
            int compared = compareScore(left.score(), right.score(), direction);
            if (compared != 0) return compared;
            compared = right.metrics().totalReturnPct().compareTo(left.metrics().totalReturnPct());
            if (compared != 0) return compared;
            compared = compareNullableDescending(left.metrics().sharpeRatio(), right.metrics().sharpeRatio());
            if (compared != 0) return compared;
            compared = left.metrics().maxDrawdownPct().compareTo(right.metrics().maxDrawdownPct());
            if (compared != 0) return compared;
            return left.participationId().compareTo(right.participationId());
        };
    }

    private static int compareScore(BigDecimal left, BigDecimal right, ScoringDirection direction) {
        return direction == ScoringDirection.HIGHER_IS_BETTER
                ? right.compareTo(left)
                : left.compareTo(right);
    }

    private static int compareNullableDescending(BigDecimal left, BigDecimal right) {
        if (left == null) return right == null ? 0 : 1;
        if (right == null) return -1;
        return right.compareTo(left);
    }

    private static boolean scoringTie(OfficialScoringResult left, OfficialScoringResult right) {
        return left.score().compareTo(right.score()) == 0
                && left.metrics().totalReturnPct().compareTo(right.metrics().totalReturnPct()) == 0
                && nullableEqual(left.metrics().sharpeRatio(), right.metrics().sharpeRatio())
                && left.metrics().maxDrawdownPct().compareTo(right.metrics().maxDrawdownPct()) == 0;
    }

    private static boolean nullableEqual(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }
}

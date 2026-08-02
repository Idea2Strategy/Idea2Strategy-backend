package com.idea2strategy.backend.application.performance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.domain.competition.CompetitionType;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LivePerformanceProjectionCalculatorTest {
    private static final UUID BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000016");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-02T04:00:00Z");
    private static final String LEDGER_HASH = "sha256:" + "a".repeat(64);
    private static final String POSITION_HASH = "sha256:" + "b".repeat(64);
    private final LivePerformanceProjectionCalculator calculator = new LivePerformanceProjectionCalculator();

    @Test
    void reconstructsOfficialLiveMetricsFromLedgerPositionAndEquityFacts() {
        var firstMetrics = new LinkedHashMap<String, Object>();
        firstMetrics.put("tradeCount", 3);
        firstMetrics.put("evaluatedDurationSeconds", 3600);
        var secondMetrics = new LinkedHashMap<String, Object>();
        secondMetrics.put("evaluatedDurationSeconds", 3600);
        secondMetrics.put("tradeCount", 3);

        var first = calculator.calculate(input(firstMetrics)).performance();
        var replayed = calculator.calculate(input(secondMetrics)).performance();

        assertThat(first.equityAmount()).isEqualByComparingTo("104250.00000000");
        assertThat(first.totalReturnPct()).isEqualByComparingTo("4.25000000");
        assertThat(first.maxDrawdownPct()).isEqualByComparingTo("5.22727273");
        assertThat(first.sharpeRatio()).isEqualByComparingTo("1.20000000");
        assertThat(first.metricsDocument()).isEqualTo("{\"evaluatedDurationSeconds\":3600,\"tradeCount\":3}");
        assertThat(first.projectionHash()).startsWith("sha256:").hasSize(71);
        assertThat(replayed).isEqualTo(first);
    }

    @Test
    void rejectsBacktestCompetitionAndBacktestSource() {
        assertThatThrownBy(() -> calculator.calculate(withProvenance(
                        CompetitionType.BACKTEST, LivePerformanceSource.LIVE_TRADING)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BACKTEST");
        assertThatThrownBy(() -> calculator.calculate(withProvenance(
                        CompetitionType.LIVE_PAPER, LivePerformanceSource.BACKTEST)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BACKTEST");
    }

    @Test
    void persistenceProvenanceCannotBeConstructedForBacktest() {
        var performance = calculator.calculate(input(Map.of())).performance();

        assertThatThrownBy(() -> new OfficialLivePerformanceProjection(
                        CompetitionType.BACKTEST, LivePerformanceSource.LIVE_TRADING, performance))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LIVE_PAPER");
        assertThatThrownBy(() -> new OfficialLivePerformanceProjection(
                        CompetitionType.LIVE_PAPER, LivePerformanceSource.BACKTEST, performance))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LIVE_TRADING");
        assertThat(OfficialLivePerformanceProjection.class.getDeclaredConstructors())
                .allSatisfy(constructor -> assertThat(Modifier.isPublic(constructor.getModifiers())).isFalse());
    }

    @Test
    void rejectsMalformedEvidenceHashesAndCalculationVersions() {
        assertThatThrownBy(() -> withEvidence("sha256:" + "A".repeat(64), POSITION_HASH, "performance-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowercase hex");
        assertThatThrownBy(() -> withEvidence(LEDGER_HASH, "position-state-v1", "performance-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positionStateHash");
        assertThatThrownBy(() -> withEvidence(LEDGER_HASH, POSITION_HASH, "bad version"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("calculationRulesVersion");
        assertThatThrownBy(() -> withEvidence(LEDGER_HASH, POSITION_HASH, "v".repeat(81)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("calculationRulesVersion");
    }

    @Test
    void structuredHashDoesNotCollapseDifferentPositionFactBoundaries() {
        var first = calculator.calculate(collisionInput(List.of(new BigDecimal("1"), new BigDecimal("23"))))
                .performance();
        var second = calculator.calculate(collisionInput(List.of(new BigDecimal("12"), new BigDecimal("3"), new BigDecimal("9"))))
                .performance();

        assertThat(first.equityAmount()).isEqualByComparingTo(second.equityAmount());
        assertThat(first.projectionHash()).isNotEqualTo(second.projectionHash());
    }

    @Test
    void positionFactOrderDoesNotChangeTheReplayProjection() {
        var first = calculator.calculate(collisionInput(List.of(new BigDecimal("15"), new BigDecimal("9"))))
                .performance();
        var reordered = calculator.calculate(collisionInput(List.of(new BigDecimal("9"), new BigDecimal("15"))))
                .performance();

        assertThat(reordered).isEqualTo(first);
    }

    @Test
    void rejectsHistoryThatDoesNotMatchCurrentLedgerAndPositionFacts() {
        var invalid = new LivePerformanceProjectionInput(
                BOT_ID,
                CompetitionType.LIVE_PAPER,
                LivePerformanceSource.LIVE_TRADING,
                new BigDecimal("100000"),
                new BigDecimal("25000"),
                List.of(new BigDecimal("50000"), new BigDecimal("29250")),
                List.of(new EquityObservation(42, new BigDecimal("99999"))),
                null,
                Map.of(),
                LEDGER_HASH,
                POSITION_HASH,
                "performance-v1",
                42,
                OCCURRED_AT);

        assertThatThrownBy(() -> calculator.calculate(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("current ledger and position facts");
    }

    private LivePerformanceProjectionInput input(Map<String, Object> metrics) {
        return new LivePerformanceProjectionInput(
                BOT_ID,
                CompetitionType.LIVE_PAPER,
                LivePerformanceSource.LIVE_TRADING,
                new BigDecimal("100000"),
                new BigDecimal("25000"),
                List.of(new BigDecimal("50000"), new BigDecimal("29250")),
                List.of(
                        new EquityObservation(0, new BigDecimal("100000")),
                        new EquityObservation(20, new BigDecimal("110000")),
                        new EquityObservation(42, new BigDecimal("104250"))),
                new BigDecimal("1.2"),
                metrics,
                LEDGER_HASH,
                POSITION_HASH,
                "performance-v1",
                42,
                OCCURRED_AT);
    }

    private LivePerformanceProjectionInput withProvenance(
            CompetitionType competitionType,
            LivePerformanceSource source) {
        var valid = input(Map.of());
        return new LivePerformanceProjectionInput(
                valid.botId(), competitionType, source, valid.initialCapitalAmount(), valid.currentCashAmount(),
                valid.positionMarketValues(), valid.equityHistory(), valid.producerCalculatedSharpeRatio(),
                valid.additionalMetrics(), valid.ledgerStateHash(), valid.positionStateHash(),
                valid.calculationRulesVersion(), valid.sourceEventSequence(), valid.occurredAt());
    }

    private LivePerformanceProjectionInput withEvidence(String ledgerHash, String positionHash, String version) {
        var valid = input(Map.of());
        return new LivePerformanceProjectionInput(
                valid.botId(), valid.competitionType(), valid.source(), valid.initialCapitalAmount(),
                valid.currentCashAmount(), valid.positionMarketValues(), valid.equityHistory(),
                valid.producerCalculatedSharpeRatio(), valid.additionalMetrics(), ledgerHash, positionHash,
                version, valid.sourceEventSequence(), valid.occurredAt());
    }

    private LivePerformanceProjectionInput collisionInput(List<BigDecimal> positionValues) {
        return new LivePerformanceProjectionInput(
                BOT_ID,
                CompetitionType.LIVE_PAPER,
                LivePerformanceSource.LIVE_TRADING,
                new BigDecimal("20"),
                BigDecimal.ZERO,
                positionValues,
                List.of(new EquityObservation(42, new BigDecimal("24"))),
                null,
                Map.of(),
                LEDGER_HASH,
                POSITION_HASH,
                "performance-v1",
                42,
                OCCURRED_AT);
    }
}

package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.performance.EquityObservation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class VirtualLiquidationServiceTest {
    private static final Instant CUTOFF = Instant.parse("2026-08-02T06:00:00Z");
    private static final Instant FINALIZED_AT = CUTOFF.plusSeconds(3);

    @Test
    void createsDeterministicScoringOnlyPerformanceFromAnFAggregateQuote() {
        var context = context();
        var saved = new AtomicReference<VirtualLiquidationPerformance>();
        var service = service(context, saved);

        var first = service.finalizeEvaluation(request());
        var second = service.finalizeEvaluation(request());

        assertThat(first).isEqualTo(VirtualLiquidationWriteDecision.CREATED);
        assertThat(second).isEqualTo(VirtualLiquidationWriteDecision.CREATED);
        assertThat(saved.get().equityAmount()).isEqualByComparingTo("109490.50000000");
        assertThat(saved.get().totalReturnPct()).isEqualByComparingTo("9.49050000");
        assertThat(saved.get().maxDrawdownPct()).isEqualByComparingTo("5.00000000");
        assertThat(saved.get().snapshotHash()).matches("sha256:[0-9a-f]{64}");
        assertThat(saved.get().virtualLiquidationDocument())
                .contains("\"positionCount\":2")
                .doesNotContain("instrument", "quantity", context.botId().toString());
    }

    @Test
    void changedAggregateCashCreatesDifferentEvidence() {
        var calculator = new VirtualLiquidationPerformanceCalculator();

        var original = calculator.calculate(context(), quote("95000", "15000", "9.50"));
        var changed = calculator.calculate(context(), quote("95000", "15000", "10.00"));

        assertThat(changed.inputHash()).isNotEqualTo(original.inputHash());
        assertThat(changed.snapshotHash()).isNotEqualTo(original.snapshotHash());
    }

    @Test
    void verifiesTheEntireCanonicalQuoteHashAndCashConservation() {
        var calculator = new VirtualLiquidationPerformanceCalculator();
        var valid = quote("95000", "15000", "9.50");

        assertThatThrownBy(() -> calculator.calculate(
                        context(), valid.withQuoteHash("sha256:" + "9".repeat(64))))
                .isInstanceOf(VirtualLiquidationConflictException.class)
                .hasMessageContaining("quote hash");

        var unsigned = unsignedQuote("95000", "15000", "500", "9.50", "14000", List.of(
                new EquityObservation(15, amount("95000")),
                new EquityObservation(10, amount("100000"))));
        var brokenConservation = unsigned.withQuoteHash(VirtualLiquidationQuoteHasher.hash(unsigned));
        assertThatThrownBy(() -> calculator.calculate(context(), brokenConservation))
                .isInstanceOf(VirtualLiquidationConflictException.class)
                .hasMessageContaining("proceeds minus cost and fee");
    }

    @Test
    void preservesNegativeCashEquityReturnAndDrawdown() {
        var calculator = new VirtualLiquidationPerformanceCalculator();
        var unsigned = unsignedQuote("-2000", "100", "5000", "0", "-4900", List.of(
                new EquityObservation(10, amount("100000")),
                new EquityObservation(15, amount("-1000"))));
        var negative = unsigned.withQuoteHash(VirtualLiquidationQuoteHasher.hash(unsigned));

        var result = calculator.calculate(context(), negative);

        assertThat(result.equityAmount()).isEqualByComparingTo("-6900.00000000");
        assertThat(result.totalReturnPct()).isEqualByComparingTo("-106.90000000");
        assertThat(result.maxDrawdownPct()).isEqualByComparingTo("106.90000000");
    }

    @Test
    void rejectsHistoryBeforeTheOfficialSegmentStart() {
        var calculator = new VirtualLiquidationPerformanceCalculator();
        var unsigned = unsignedQuote("95000", "15000", "500", "9.50", "14490.50", List.of(
                new EquityObservation(9, amount("100000"))));
        var quote = unsigned.withQuoteHash(VirtualLiquidationQuoteHasher.hash(unsigned));

        assertThatThrownBy(() -> calculator.calculate(context(), quote))
                .isInstanceOf(VirtualLiquidationConflictException.class)
                .hasMessageContaining("equity history");
    }

    @Test
    void rejectsWrongCutoffSequencePolicyAndMalformedHashes() {
        var calculator = new VirtualLiquidationPerformanceCalculator();

        assertThatThrownBy(() -> calculator.calculate(context(), quoteAt(CUTOFF.minusSeconds(1), 20, FEE_ID,
                        "sha256:" + "a".repeat(64))))
                .isInstanceOf(VirtualLiquidationConflictException.class)
                .hasMessageContaining("cutoff");
        assertThatThrownBy(() -> calculator.calculate(context(), quoteAt(CUTOFF, 9, FEE_ID,
                        "sha256:" + "a".repeat(64))))
                .isInstanceOf(VirtualLiquidationConflictException.class)
                .hasMessageContaining("sequence");
        assertThatThrownBy(() -> calculator.calculate(context(), quoteAt(CUTOFF, 20, id(99),
                        "sha256:" + "a".repeat(64))))
                .isInstanceOf(VirtualLiquidationConflictException.class)
                .hasMessageContaining("fee policy");
        assertThatThrownBy(() -> quoteAt(CUTOFF, 20, FEE_ID, "not-a-hash"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quoteHash");
    }

    @Test
    void refusesToFinalizeBeforeTheOfficialCutoff() {
        var context = context();
        var service = new VirtualLiquidationService(
                request -> context,
                loaded -> quote("95000", "15000", "9.50"),
                (loaded, performance, at) -> VirtualLiquidationWriteDecision.CREATED,
                new VirtualLiquidationPerformanceCalculator(),
                Clock.fixed(CUTOFF.minusNanos(1), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.finalizeEvaluation(request()))
                .isInstanceOf(VirtualLiquidationConflictException.class)
                .hasMessageContaining("cutoff");
    }

    private static VirtualLiquidationService service(
            VirtualLiquidationContext context,
            AtomicReference<VirtualLiquidationPerformance> saved) {
        return new VirtualLiquidationService(
                request -> context,
                loaded -> quote("95000", "15000", "9.50"),
                (loaded, performance, at) -> {
                    assertThat(loaded).isEqualTo(context);
                    assertThat(at).isEqualTo(FINALIZED_AT);
                    saved.set(performance);
                    return VirtualLiquidationWriteDecision.CREATED;
                },
                new VirtualLiquidationPerformanceCalculator(),
                Clock.fixed(FINALIZED_AT, ZoneOffset.UTC));
    }

    private static VirtualLiquidationRequest request() {
        return new VirtualLiquidationRequest(PARTICIPATION_ID, SEGMENT_ID);
    }

    private static VirtualLiquidationContext context() {
        return new VirtualLiquidationContext(
                ROOM_ID, PARTICIPATION_ID, BOT_ID, SEGMENT_ID,
                CUTOFF.minusSeconds(600), CUTOFF, 10, amount("100000"), FEE_ID,
                "sha256:" + "b".repeat(64), 5, "sha256:" + "c".repeat(64));
    }

    private static VirtualLiquidationQuote quote(String cash, String proceeds, String fee) {
        BigDecimal delta = amount(proceeds).subtract(amount("500")).subtract(amount(fee));
        var quote = unsignedQuote(cash, proceeds, "500", fee, delta.toPlainString(), List.of(
                new EquityObservation(10, amount("100000")),
                new EquityObservation(15, amount("95000"))));
        return quote.withQuoteHash(VirtualLiquidationQuoteHasher.hash(quote));
    }

    private static VirtualLiquidationQuote unsignedQuote(
            String cash,
            String proceeds,
            String cost,
            String fee,
            String delta,
            List<EquityObservation> history) {
        return new VirtualLiquidationQuote(
                ROOM_ID, PARTICIPATION_ID, BOT_ID, SEGMENT_ID, CUTOFF, 20,
                amount(cash), amount(delta), history,
                amount("1.25"), 2, amount(proceeds), amount(cost), amount(fee),
                FEE_ID, "sha256:" + "b".repeat(64), 5,
                "sha256:" + "d".repeat(64), "sha256:" + "e".repeat(64),
                "sha256:" + "f".repeat(64), VirtualLiquidationQuote.CONTRACT_VERSION,
                "f-virtual-liquidation.v1", "sha256:" + "0".repeat(64));
    }

    private static VirtualLiquidationQuote quoteAt(
            Instant cutoff, long sequence, UUID feePolicyId, String quoteHash) {
        var quote = quote("95000", "15000", "9.50");
        return new VirtualLiquidationQuote(
                quote.roomId(), quote.participationId(), quote.botId(), quote.evaluationSegmentId(), cutoff, sequence,
                quote.currentCashAmount(), quote.netLiquidationCashDelta(), quote.equityHistory(),
                quote.producerCalculatedSharpeRatio(), quote.liquidatedPositionCount(), quote.grossProceedsAmount(),
                quote.grossCostAmount(), quote.feeAmount(), feePolicyId, quote.feeRulesHash(),
                quote.slippageRateBps(), quote.ledgerStateHash(), quote.positionStateHash(), quote.sourceSetHash(),
                quote.quoteContractVersion(), quote.quoteRulesVersion(), quoteHash);
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("b8000000-0000-4000-8000-" + String.format("%012d", suffix));
    }

    private static final UUID ROOM_ID = id(1);
    private static final UUID PARTICIPATION_ID = id(2);
    private static final UUID BOT_ID = id(3);
    private static final UUID SEGMENT_ID = id(4);
    private static final UUID FEE_ID = id(5);
}

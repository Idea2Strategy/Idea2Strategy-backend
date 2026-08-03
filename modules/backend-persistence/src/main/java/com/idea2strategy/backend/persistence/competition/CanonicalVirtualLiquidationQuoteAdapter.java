package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.VirtualLiquidationContext;
import com.idea2strategy.backend.application.competition.VirtualLiquidationQuote;
import com.idea2strategy.backend.application.competition.VirtualLiquidationQuoteHasher;
import com.idea2strategy.backend.application.competition.VirtualLiquidationQuotePort;
import com.idea2strategy.backend.application.performance.EquityObservation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * F93: the virtual liquidation quote, computed from the canonical trading record.
 *
 * <p>Everything here is read as of the segment's half-open cutoff — fills at or after
 * {@code endsAt} do not exist for this quote, which is what E's boundary tests demand.
 *
 * <p><strong>The v1 mark.</strong> Canonical storage has no queryable price table: prices live in
 * D's dataset objects, not rows. What it does have is the reference price of every official fill,
 * evidence-hashed at execution time. A position only exists because fills opened it, so every held
 * instrument necessarily has one. The v1 valuation rule is therefore: <em>an instrument is marked
 * at the latest canonical fill reference price observed anywhere in the engine before the
 * cutoff</em>. It is stale by exactly the time since the instrument last traded, it is official
 * rather than invented, and it is replaced wholesale when D ships a queryable official close —
 * that swap changes {@code quoteRulesVersion}, so no old quote can be mistaken for a new one.
 *
 * <p>The hypothetical closing trades follow F's own execution model: the fixed slippage and the
 * pinned fee policy the room locked at start, applied against the mark. Longs sell below the mark,
 * shorts cover above it, and the fee takes its cut of both — the same pessimism a real stop
 * settlement's liquidation intents would face.
 */
@Repository
public class CanonicalVirtualLiquidationQuoteAdapter implements VirtualLiquidationQuotePort {

    public static final String QUOTE_RULES_VERSION = "virtual-liquidation-quote-rules.v1";

    private static final int SCALE = 8;
    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);

    /** Segment fills, in official event order, with the side and instrument of their order. */
    private static final String SEGMENT_FILLS = """
            select f.id as fill_id, f.settlement_cash_delta, f.reference_price, f.quantity,
                   e.event_sequence, o.instrument_id, cast(o.side as varchar) as side
            from trading.fills f
            join bot.bot_events e on e.id = f.bot_event_id
            join trading.orders o on o.id = f.order_id
            where f.bot_id = :bot and f.occurred_at >= :startsAt and f.occurred_at < :endsAt
            order by e.event_sequence
            """;

    /** The latest official reference price for one instrument, engine-wide, before the cutoff. */
    private static final String LATEST_MARK = """
            select f.reference_price
            from trading.fills f
            join trading.orders o on o.id = f.order_id
            where o.instrument_id = :instrument and f.occurred_at < :endsAt
            order by f.occurred_at desc, f.id
            limit 1
            """;

    private static final String FEE_RATE = """
            select fee_rate_bps from trading.fee_policy_versions where id = :feePolicyId
            """;

    /** What the bot already held when the segment opened, from its own official fills. */
    private static final String OPENING_POSITIONS = """
            select o.instrument_id,
                   sum(case when cast(o.side as varchar) = 'BUY' then f.quantity
                            else -f.quantity end) as net_quantity
            from trading.fills f
            join trading.orders o on o.id = f.order_id
            where f.bot_id = :bot and f.occurred_at < :startsAt
            group by o.instrument_id
            having sum(case when cast(o.side as varchar) = 'BUY' then f.quantity
                            else -f.quantity end) <> 0
            """;

    private final JdbcClient jdbc;

    public CanonicalVirtualLiquidationQuoteAdapter(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public VirtualLiquidationQuote load(VirtualLiquidationContext context) {
        Objects.requireNonNull(context, "context");
        BigDecimal feeRate = BigDecimal.valueOf(feeRateBps(context.feePolicyId())).divide(BPS);
        BigDecimal slippage = BigDecimal.valueOf(context.slippageRateBps()).divide(BPS);

        List<SegmentFill> fills = jdbc.sql(SEGMENT_FILLS)
                .param("bot", context.botId())
                .param("startsAt", offset(context.startsAt()))
                .param("endsAt", offset(context.endsAt()))
                .query((rs, row) -> new SegmentFill(
                        rs.getObject("fill_id", UUID.class),
                        rs.getLong("event_sequence"),
                        rs.getObject("instrument_id", UUID.class),
                        rs.getString("side"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("reference_price"),
                        rs.getBigDecimal("settlement_cash_delta")))
                .list();

        // A room bot is born for its room, so this is normally empty; a bot that nevertheless
        // carried holdings into the segment has them liquidated too — the cutoff closes what the
        // bot holds, not merely what the segment traded.
        Map<UUID, BigDecimal> netQuantity = new LinkedHashMap<>();
        Map<UUID, BigDecimal> marks = new LinkedHashMap<>();
        jdbc.sql(OPENING_POSITIONS)
                .param("bot", context.botId())
                .param("startsAt", offset(context.startsAt()))
                .query((rs, row) -> Map.entry(
                        rs.getObject("instrument_id", UUID.class),
                        rs.getBigDecimal("net_quantity")))
                .list()
                .forEach(opening -> {
                    netQuantity.put(opening.getKey(), opening.getValue());
                    marks.put(opening.getKey(),
                            markOf(opening.getKey(), context.startsAt(), Map.of()));
                });

        // One walk gives everything the quote owes: the cash after each fill, the net quantity per
        // instrument, the freshest mark seen so far, and an equity observation per official event.
        // The calculator demands every observation sit strictly inside [start, source), and it
        // appends the final liquidated equity itself, so the observations here are exactly the
        // fills and the source sequence is the pseudo-event after the last of them.
        BigDecimal cash = scaled(context.initialCapitalAmount());
        List<EquityObservation> history = new ArrayList<>();
        long lastSequence = context.startEventSequence() - 1;
        for (SegmentFill fill : fills) {
            cash = cash.add(fill.settlementCashDelta()).setScale(SCALE, RoundingMode.HALF_EVEN);
            BigDecimal signed = "BUY".equals(fill.side()) ? fill.quantity() : fill.quantity().negate();
            netQuantity.merge(fill.instrumentId(), signed, BigDecimal::add);
            marks.put(fill.instrumentId(), fill.referencePrice());
            lastSequence = fill.eventSequence();
            history.add(new EquityObservation(lastSequence, equity(cash, netQuantity, marks)));
        }
        long sourceEventSequence = fills.isEmpty()
                ? context.startEventSequence()
                : lastSequence + 1;
        netQuantity.values().removeIf(quantity -> quantity.signum() == 0);

        // The hypothetical close of what remains, priced by the v1 mark under F's execution model.
        BigDecimal proceeds = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal fee = BigDecimal.ZERO;
        int liquidated = 0;
        for (Map.Entry<UUID, BigDecimal> position : netQuantity.entrySet()) {
            BigDecimal mark = markOf(position.getKey(), context.endsAt(), marks);
            BigDecimal quantity = position.getValue().abs();
            liquidated++;
            if (position.getValue().signum() > 0) {
                BigDecimal executionPrice = mark.multiply(BigDecimal.ONE.subtract(slippage));
                BigDecimal gross = quantity.multiply(executionPrice);
                proceeds = proceeds.add(gross);
                fee = fee.add(gross.multiply(feeRate));
            } else {
                BigDecimal executionPrice = mark.multiply(BigDecimal.ONE.add(slippage));
                BigDecimal gross = quantity.multiply(executionPrice);
                cost = cost.add(gross);
                fee = fee.add(gross.multiply(feeRate));
            }
        }
        proceeds = scaled(proceeds);
        cost = scaled(cost);
        fee = scaled(fee);
        // The calculator refuses a delta that does not preserve proceeds minus cost and fee, so the
        // delta is derived from the rounded aggregates rather than rounded independently.
        BigDecimal netDelta = proceeds.subtract(cost).subtract(fee).setScale(SCALE);

        VirtualLiquidationQuote unhashed = quote(
                context, sourceEventSequence, cash, netDelta, List.copyOf(history), liquidated,
                proceeds, cost, fee,
                ledgerStateHash(fills), positionStateHash(netQuantity, marks),
                sourceSetHash(fills), "sha256:" + "0".repeat(64));
        return quote(
                context, sourceEventSequence, cash, netDelta, List.copyOf(history), liquidated,
                proceeds, cost, fee,
                ledgerStateHash(fills), positionStateHash(netQuantity, marks),
                sourceSetHash(fills), VirtualLiquidationQuoteHasher.hash(unhashed));
    }

    private VirtualLiquidationQuote quote(
            VirtualLiquidationContext context, long sourceEventSequence, BigDecimal cash,
            BigDecimal netDelta, List<EquityObservation> history, int liquidated,
            BigDecimal proceeds, BigDecimal cost, BigDecimal fee,
            String ledgerStateHash, String positionStateHash, String sourceSetHash,
            String quoteHash) {
        return new VirtualLiquidationQuote(
                context.roomId(),
                context.participationId(),
                context.botId(),
                context.evaluationSegmentId(),
                context.endsAt(),
                sourceEventSequence,
                cash,
                netDelta,
                history,
                // E23 recalculates ratios from the equity history it verifies; a producer-claimed
                // Sharpe adds nothing it would trust, so none is claimed.
                null,
                liquidated,
                proceeds,
                cost,
                fee,
                context.feePolicyId(),
                context.feeRulesHash(),
                context.slippageRateBps(),
                ledgerStateHash,
                positionStateHash,
                sourceSetHash,
                VirtualLiquidationQuote.CONTRACT_VERSION,
                QUOTE_RULES_VERSION,
                quoteHash);
    }

    private BigDecimal markOf(UUID instrumentId, Instant endsAt, Map<UUID, BigDecimal> segmentMarks) {
        // A position opened before the segment may not have traded inside it; the engine-wide
        // latest official reference price before the cutoff covers that case.
        BigDecimal inSegment = segmentMarks.get(instrumentId);
        BigDecimal latest = jdbc.sql(LATEST_MARK)
                .param("instrument", instrumentId)
                .param("endsAt", offset(endsAt))
                .query(BigDecimal.class)
                .optional()
                .orElse(inSegment);
        if (latest == null) {
            throw new IllegalStateException(
                    "no canonical reference price exists for instrument " + instrumentId
                            + " — a position cannot exist without the fill that opened it");
        }
        return latest;
    }

    private static BigDecimal equity(
            BigDecimal cash, Map<UUID, BigDecimal> netQuantity, Map<UUID, BigDecimal> marks) {
        BigDecimal positions = BigDecimal.ZERO;
        for (Map.Entry<UUID, BigDecimal> holding : netQuantity.entrySet()) {
            BigDecimal mark = marks.get(holding.getKey());
            if (mark != null && holding.getValue().signum() != 0) {
                positions = positions.add(holding.getValue().multiply(mark));
            }
        }
        return cash.add(positions).setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    private int feeRateBps(UUID feePolicyId) {
        return jdbc.sql(FEE_RATE)
                .param("feePolicyId", feePolicyId)
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "the pinned fee policy " + feePolicyId + " does not exist"));
    }

    /** The exact cash movements the quote consumed, in event order. */
    private static String ledgerStateHash(List<SegmentFill> fills) {
        StringBuilder text = new StringBuilder("ledger-state:v1");
        fills.forEach(fill -> text.append('|').append(fill.fillId())
                .append(':').append(fill.settlementCashDelta().toPlainString()));
        return sha256(text.toString());
    }

    /** The remainder being closed and the marks it is closed at. */
    private static String positionStateHash(
            Map<UUID, BigDecimal> netQuantity, Map<UUID, BigDecimal> marks) {
        StringBuilder text = new StringBuilder("position-state:v1");
        netQuantity.forEach((instrument, quantity) -> text.append('|').append(instrument)
                .append(':').append(quantity.toPlainString())
                .append('@').append(String.valueOf(marks.get(instrument))));
        return sha256(text.toString());
    }

    private static String sourceSetHash(List<SegmentFill> fills) {
        StringBuilder text = new StringBuilder("source-set:v1");
        fills.forEach(fill -> text.append('|').append(fill.fillId()));
        return sha256(text.toString());
    }

    private static String sha256(String text) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required", unavailable);
        }
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private record SegmentFill(
            UUID fillId, long eventSequence, UUID instrumentId, String side,
            BigDecimal quantity, BigDecimal referencePrice, BigDecimal settlementCashDelta) {}
}

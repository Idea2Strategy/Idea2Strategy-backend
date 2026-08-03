package com.idea2strategy.backend.persistence.performance;

import com.idea2strategy.backend.application.performance.BotCurrentPerformanceCommandPort;
import com.idea2strategy.backend.application.performance.BotCurrentPerformanceCommandService;
import com.idea2strategy.backend.application.performance.EquityObservation;
import com.idea2strategy.backend.application.performance.LivePerformanceProjectionInput;
import com.idea2strategy.backend.application.performance.LivePerformanceSource;
import com.idea2strategy.backend.application.performance.ProjectionWriteDecision;
import com.idea2strategy.backend.domain.competition.CompetitionType;
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
 * F93: the live performance projection, fed from the canonical trading record.
 *
 * <p>E16's projection calculator, E23's provenance hashes and the {@code bot_current_projections}
 * write path were all merged and waited on one producer: nothing turned canonical fills into
 * {@link LivePerformanceProjectionInput}. This walks each active LIVE_PAPER evaluation segment's
 * fills inside its half-open {@code [starts_at, ends_at)} window and projects the result.
 *
 * <p>Valuation uses the same v1 mark as the virtual liquidation quote — the latest canonical fill
 * reference price before the segment end — under the same rules version discipline, so the live
 * curve and the final liquidated equity are two readings of one rule rather than two opinions.
 *
 * <p>Replay safety is the consumer's: the command port ignores a projection whose event sequence
 * is not newer than the stored one, so re-feeding an unchanged segment is a no-op by design.
 */
@Repository
public class CanonicalLivePerformanceFeed {

    public static final String CALCULATION_RULES_VERSION = "live-performance-feed.v1";

    private static final int SCALE = 8;

    /** Every LIVE_PAPER segment still being evaluated, with the room's locked economics. */
    private static final String ACTIVE_SEGMENTS = """
            select p.bot_id, s.starts_at, s.ends_at, s.start_event_sequence,
                   rr.initial_cash_amount
            from competition.participations p
            join competition.rooms r on r.id = p.room_id
            join competition.live_evaluation_segments s on s.participation_id = p.id
            join competition.room_rules rr on rr.room_id = r.id
            where r.competition_type = 'LIVE_PAPER'::competition.competition_type
              and p.status = 'EVALUATING'::competition.participation_status
              and s.segment_type = 'OFFICIAL_EVALUATION'
              and s.finalized_at is null
            order by p.bot_id
            """;

    private static final String SEGMENT_FILLS = """
            select f.id as fill_id, f.settlement_cash_delta, f.reference_price, f.quantity,
                   f.occurred_at, e.event_sequence, o.instrument_id,
                   cast(o.side as varchar) as side
            from trading.fills f
            join bot.bot_events e on e.id = f.bot_event_id
            join trading.orders o on o.id = f.order_id
            where f.bot_id = :bot and f.occurred_at >= :startsAt and f.occurred_at < :endsAt
            order by e.event_sequence
            """;

    private final JdbcClient jdbc;
    private final BotCurrentPerformanceCommandService projections;

    public CanonicalLivePerformanceFeed(JdbcClient jdbc, BotCurrentPerformanceCommandPort commands) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.projections = new BotCurrentPerformanceCommandService(
                Objects.requireNonNull(commands, "commands"));
    }

    /** Projects every active segment once; returns how many projections actually moved. */
    public int refreshActiveSegments() {
        List<ActiveSegment> segments = jdbc.sql(ACTIVE_SEGMENTS)
                .query((rs, row) -> new ActiveSegment(
                        rs.getObject("bot_id", UUID.class),
                        rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
                        rs.getLong("start_event_sequence"),
                        rs.getBigDecimal("initial_cash_amount")))
                .list();
        int applied = 0;
        for (ActiveSegment segment : segments) {
            if (projections.project(input(segment)) == ProjectionWriteDecision.APPLIED) {
                applied++;
            }
        }
        return applied;
    }

    private LivePerformanceProjectionInput input(ActiveSegment segment) {
        List<SegmentFill> fills = jdbc.sql(SEGMENT_FILLS)
                .param("bot", segment.botId())
                .param("startsAt", offset(segment.startsAt()))
                .param("endsAt", offset(segment.endsAt()))
                .query((rs, row) -> new SegmentFill(
                        rs.getObject("fill_id", UUID.class),
                        rs.getLong("event_sequence"),
                        rs.getObject("instrument_id", UUID.class),
                        rs.getString("side"),
                        rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("reference_price"),
                        rs.getBigDecimal("settlement_cash_delta"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();

        // The calculator requires the last observation to equal the current facts, so the walk and
        // the reported facts are one computation. The baseline observation is the segment's start:
        // the room's initial capital, before any fill.
        BigDecimal cash = segment.initialCashAmount().setScale(SCALE, RoundingMode.HALF_EVEN);
        Map<UUID, BigDecimal> netQuantity = new LinkedHashMap<>();
        Map<UUID, BigDecimal> marks = new LinkedHashMap<>();
        List<EquityObservation> history = new ArrayList<>();
        history.add(new EquityObservation(segment.startEventSequence(), cash));
        long sourceEventSequence = segment.startEventSequence();
        Instant occurredAt = segment.startsAt();
        for (SegmentFill fill : fills) {
            cash = cash.add(fill.settlementCashDelta()).setScale(SCALE, RoundingMode.HALF_EVEN);
            BigDecimal signed = "BUY".equals(fill.side())
                    ? fill.quantity() : fill.quantity().negate();
            netQuantity.merge(fill.instrumentId(), signed, BigDecimal::add);
            marks.put(fill.instrumentId(), fill.referencePrice());
            sourceEventSequence = fill.eventSequence();
            occurredAt = fill.occurredAt();
            history.add(new EquityObservation(
                    sourceEventSequence, cash.add(positionValue(netQuantity, marks))
                            .setScale(SCALE, RoundingMode.HALF_EVEN)));
        }

        List<BigDecimal> positionValues = new ArrayList<>();
        netQuantity.forEach((instrument, quantity) -> {
            if (quantity.signum() < 0) {
                // A Basic room bot cannot be net short; a short here means the walk and the lots
                // disagree, and a wrong equity curve is worse than a loud stop.
                throw new IllegalStateException(
                        "bot " + instrument + " is net short in a LIVE_PAPER segment");
            }
            if (quantity.signum() > 0) {
                positionValues.add(quantity.multiply(marks.get(instrument))
                        .setScale(SCALE, RoundingMode.HALF_EVEN));
            }
        });

        return new LivePerformanceProjectionInput(
                segment.botId(),
                CompetitionType.LIVE_PAPER,
                LivePerformanceSource.LIVE_TRADING,
                segment.initialCashAmount(),
                cash,
                positionValues,
                List.copyOf(history),
                null,
                Map.of("feed", CALCULATION_RULES_VERSION, "fillCount", fills.size()),
                ledgerStateHash(fills),
                positionStateHash(netQuantity, marks),
                CALCULATION_RULES_VERSION,
                sourceEventSequence,
                occurredAt);
    }

    private static BigDecimal positionValue(
            Map<UUID, BigDecimal> netQuantity, Map<UUID, BigDecimal> marks) {
        BigDecimal value = BigDecimal.ZERO;
        for (Map.Entry<UUID, BigDecimal> holding : netQuantity.entrySet()) {
            if (holding.getValue().signum() != 0) {
                value = value.add(holding.getValue().multiply(marks.get(holding.getKey())));
            }
        }
        return value;
    }

    private static String ledgerStateHash(List<SegmentFill> fills) {
        StringBuilder text = new StringBuilder("live-ledger-state:v1");
        fills.forEach(fill -> text.append('|').append(fill.fillId())
                .append(':').append(fill.settlementCashDelta().toPlainString()));
        return sha256(text.toString());
    }

    private static String positionStateHash(
            Map<UUID, BigDecimal> netQuantity, Map<UUID, BigDecimal> marks) {
        StringBuilder text = new StringBuilder("live-position-state:v1");
        netQuantity.forEach((instrument, quantity) -> text.append('|').append(instrument)
                .append(':').append(quantity.toPlainString())
                .append('@').append(String.valueOf(marks.get(instrument))));
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

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private record ActiveSegment(
            UUID botId, Instant startsAt, Instant endsAt, long startEventSequence,
            BigDecimal initialCashAmount) {}

    private record SegmentFill(
            UUID fillId, long eventSequence, UUID instrumentId, String side,
            BigDecimal quantity, BigDecimal referencePrice, BigDecimal settlementCashDelta,
            Instant occurredAt) {}
}

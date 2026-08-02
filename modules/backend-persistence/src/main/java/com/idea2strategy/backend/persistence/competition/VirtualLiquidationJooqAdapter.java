package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.VirtualLiquidationConflictException;
import com.idea2strategy.backend.application.competition.VirtualLiquidationContext;
import com.idea2strategy.backend.application.competition.VirtualLiquidationContextPort;
import com.idea2strategy.backend.application.competition.VirtualLiquidationNotFoundException;
import com.idea2strategy.backend.application.competition.VirtualLiquidationPerformance;
import com.idea2strategy.backend.application.competition.VirtualLiquidationRequest;
import com.idea2strategy.backend.application.competition.VirtualLiquidationResultPort;
import com.idea2strategy.backend.application.competition.VirtualLiquidationWriteDecision;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class VirtualLiquidationJooqAdapter
        implements VirtualLiquidationContextPort, VirtualLiquidationResultPort {
    private static final String CONTEXT_SELECT = """
            select r.id as room_id, p.id as participation_id, p.bot_id,
                   s.id as evaluation_segment_id, s.starts_at, s.ends_at, s.start_event_sequence,
                   rr.initial_cash_amount, rr.fee_policy_id, fp.rules_hash as fee_rules_hash,
                   rr.slippage_rate_bps, rr.rules_hash as room_rules_hash,
                   s.end_event_sequence, s.final_state_hash, s.source_set_hash,
                   s.virtual_liquidation_document::text as virtual_liquidation_document, s.finalized_at
              from competition.participations p
              join competition.rooms r on r.id = p.room_id
              join competition.live_evaluation_segments s on s.participation_id = p.id
              join competition.room_rules rr on rr.room_id = r.id
              join trading.fee_policy_versions fp on fp.id = rr.fee_policy_id
             where p.id = ? and s.id = ?
               and (s.finalized_at is not null
                    or p.status = 'EVALUATING'::competition.participation_status)
               and r.competition_type = 'LIVE_PAPER'::competition.competition_type
               and s.segment_type = 'OFFICIAL_EVALUATION'
            """;

    private final DSLContext dsl;

    public VirtualLiquidationJooqAdapter(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    @Transactional(readOnly = true)
    public VirtualLiquidationContext load(VirtualLiquidationRequest request) {
        Objects.requireNonNull(request, "request");
        Record record = dsl.fetchOne(CONTEXT_SELECT, request.participationId(), request.evaluationSegmentId());
        if (record == null) {
            throw new VirtualLiquidationNotFoundException();
        }
        return toContext(record);
    }

    @Override
    @Transactional
    public VirtualLiquidationWriteDecision save(
            VirtualLiquidationContext context,
            VirtualLiquidationPerformance performance,
            Instant finalizedAt) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(performance, "performance");
        Objects.requireNonNull(finalizedAt, "finalizedAt");

        Record locked = dsl.fetchOne(
                CONTEXT_SELECT + " for update of p, s",
                context.participationId(), context.evaluationSegmentId());
        if (locked == null) {
            throw new VirtualLiquidationNotFoundException();
        }
        VirtualLiquidationContext lockedContext = toContext(locked);
        if (!lockedContext.equals(context)) {
            throw new VirtualLiquidationConflictException("locked virtual liquidation context changed");
        }
        if (!context.botId().equals(performance.botId())
                || !context.evaluationSegmentId().equals(performance.evaluationSegmentId())
                || !context.endsAt().equals(performance.evaluatedAt())
                || performance.sourceEventSequence() < context.startEventSequence()) {
            throw new VirtualLiquidationConflictException("virtual liquidation result boundary does not match");
        }

        OffsetDateTime existingFinalizedAt = locked.get("finalized_at", OffsetDateTime.class);
        if (existingFinalizedAt != null) {
            return identicalExisting(locked, performance)
                    ? VirtualLiquidationWriteDecision.ALREADY_FINALIZED_IDENTICALLY
                    : conflict();
        }
        if (finalizedAt.isBefore(context.endsAt())) {
            throw new VirtualLiquidationConflictException("segment cannot finalize before its cutoff");
        }

        dsl.execute(
                """
                insert into performance.bot_snapshots
                    (id, bot_id, snapshot_type, source_event_sequence, evaluated_at,
                     equity_amount, total_return_pct, max_drawdown_pct, sharpe_ratio,
                     metrics_document, input_hash, calculation_rules_version, snapshot_hash, created_at)
                values (?, ?, 'LEADERBOARD_CUTOFF'::performance.snapshot_type, ?, ?::timestamptz,
                        ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::timestamptz)
                """,
                performance.snapshotId(), performance.botId(), performance.sourceEventSequence(),
                utc(performance.evaluatedAt()), performance.equityAmount(), performance.totalReturnPct(),
                performance.maxDrawdownPct(), performance.sharpeRatio(), performance.metricsDocument(),
                performance.inputHash(), performance.calculationRulesVersion(), performance.snapshotHash(),
                utc(finalizedAt));
        int updated = dsl.execute(
                """
                update competition.live_evaluation_segments
                   set end_event_sequence = ?, final_state_hash = ?, source_set_hash = ?,
                       virtual_liquidation_document = ?::jsonb, finalized_at = ?::timestamptz
                 where id = ? and participation_id = ? and finalized_at is null
                """,
                performance.sourceEventSequence(), performance.finalStateHash(), performance.sourceSetHash(),
                performance.virtualLiquidationDocument(), utc(finalizedAt), context.evaluationSegmentId(),
                context.participationId());
        if (updated != 1) {
            throw new VirtualLiquidationConflictException("official evaluation segment finalization lost its lock");
        }
        return VirtualLiquidationWriteDecision.CREATED;
    }

    private boolean identicalExisting(Record segment, VirtualLiquidationPerformance performance) {
        if (!Objects.equals(segment.get("end_event_sequence", Long.class), performance.sourceEventSequence())
                || !Objects.equals(segment.get("final_state_hash", String.class), performance.finalStateHash())
                || !Objects.equals(segment.get("source_set_hash", String.class), performance.sourceSetHash())) {
            return false;
        }
        String storedDocument = segment.get("virtual_liquidation_document", String.class);
        Object storedJson = dsl.fetchValue("select ?::jsonb", storedDocument);
        Object expectedJson = dsl.fetchValue("select ?::jsonb", performance.virtualLiquidationDocument());
        if (!Objects.equals(storedJson, expectedJson)) {
            return false;
        }
        Record snapshot = dsl.fetchOne(
                """
                select id, bot_id, source_event_sequence, evaluated_at,
                       equity_amount, total_return_pct, max_drawdown_pct, sharpe_ratio,
                       metrics_document::text as metrics_document, input_hash,
                       calculation_rules_version, snapshot_hash
                  from performance.bot_snapshots
                 where id = ? and snapshot_type = 'LEADERBOARD_CUTOFF'::performance.snapshot_type
                """,
                performance.snapshotId());
        return snapshot != null
                && Objects.equals(snapshot.get("bot_id", UUID.class), performance.botId())
                && Objects.equals(snapshot.get("source_event_sequence", Long.class), performance.sourceEventSequence())
                && Objects.equals(snapshot.get("evaluated_at", OffsetDateTime.class).toInstant(), performance.evaluatedAt())
                && sameAmount(snapshot.get("equity_amount", BigDecimal.class), performance.equityAmount())
                && sameAmount(snapshot.get("total_return_pct", BigDecimal.class), performance.totalReturnPct())
                && sameAmount(snapshot.get("max_drawdown_pct", BigDecimal.class), performance.maxDrawdownPct())
                && sameAmount(snapshot.get("sharpe_ratio", BigDecimal.class), performance.sharpeRatio())
                && sameJson(snapshot.get("metrics_document", String.class), performance.metricsDocument())
                && Objects.equals(snapshot.get("input_hash", String.class), performance.inputHash())
                && Objects.equals(snapshot.get("calculation_rules_version", String.class),
                        performance.calculationRulesVersion())
                && Objects.equals(snapshot.get("snapshot_hash", String.class), performance.snapshotHash());
    }

    private boolean sameJson(String stored, String expected) {
        Object storedJson = dsl.fetchValue("select ?::jsonb", stored);
        Object expectedJson = dsl.fetchValue("select ?::jsonb", expected);
        return Objects.equals(storedJson, expectedJson);
    }

    private static boolean sameAmount(BigDecimal stored, BigDecimal expected) {
        return stored == null ? expected == null : expected != null && stored.compareTo(expected) == 0;
    }

    private static VirtualLiquidationWriteDecision conflict() {
        throw new VirtualLiquidationConflictException(
                "official evaluation segment was already finalized with different evidence");
    }

    private static VirtualLiquidationContext toContext(Record record) {
        return new VirtualLiquidationContext(
                record.get("room_id", UUID.class),
                record.get("participation_id", UUID.class),
                record.get("bot_id", UUID.class),
                record.get("evaluation_segment_id", UUID.class),
                record.get("starts_at", OffsetDateTime.class).toInstant(),
                record.get("ends_at", OffsetDateTime.class).toInstant(),
                record.get("start_event_sequence", Long.class),
                record.get("initial_cash_amount", BigDecimal.class),
                record.get("fee_policy_id", UUID.class),
                record.get("fee_rules_hash", String.class),
                record.get("slippage_rate_bps", Integer.class),
                record.get("room_rules_hash", String.class));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

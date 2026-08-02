package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.ScoringEvidenceNotFoundException;
import com.idea2strategy.backend.application.competition.ScoringEvidencePort;
import com.idea2strategy.backend.application.competition.ScoringEvidenceRequest;
import com.idea2strategy.backend.application.competition.ScoringEvidenceSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ScoringEvidenceJooqAdapter implements ScoringEvidencePort {
    private final DSLContext dsl;

    public ScoringEvidenceJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional(readOnly = true)
    public ScoringEvidenceSource load(ScoringEvidenceRequest request) {
        var evidence = dsl.fetch(
                "select r.id as room_id, p.id as participation_id, p.bot_id, "
                        + "s.id as evaluation_segment_id, s.starts_at as segment_starts_at, "
                        + "s.ends_at as segment_ends_at, s.start_event_sequence, s.end_event_sequence, "
                        + "s.initial_state_hash, s.final_state_hash, s.source_set_hash, "
                        + "s.finalized_at as segment_finalized_at, "
                        + "ps.id as performance_snapshot_id, ps.source_event_sequence as performance_source_sequence, "
                        + "ps.evaluated_at as performance_evaluated_at, ps.input_hash as performance_input_hash, "
                        + "ps.calculation_rules_version as performance_calculation_rules_version, "
                        + "ps.snapshot_hash as performance_snapshot_hash, "
                        + "ps.equity_amount, ps.total_return_pct, ps.max_drawdown_pct, ps.sharpe_ratio, "
                        + "ps.metrics_document::text as performance_metrics, "
                        + "rr.rules_hash as room_rules_hash, rr.scoring_parameters::text as scoring_parameters, "
                        + "locked.id as locked_template_id, locked.rules_hash as locked_template_rules_hash, "
                        + "calculation.id as calculation_template_id, "
                        + "calculation.template_code as calculation_template_code, "
                        + "calculation.version as calculation_template_version, "
                        + "calculation.rules_document::text as calculation_template_rules, "
                        + "calculation.rules_hash as calculation_template_rules_hash "
                        + "from competition.participations p "
                        + "join competition.rooms r on r.id = p.room_id "
                        + "join competition.live_evaluation_segments s on s.participation_id = p.id "
                        + "join performance.bot_snapshots ps on ps.bot_id = p.bot_id "
                        + "join competition.room_rules rr on rr.room_id = r.id "
                        + "join competition.scoring_template_versions locked "
                        + "on locked.id = rr.scoring_template_version_id "
                        + "join competition.scoring_template_versions calculation on calculation.id = ? "
                        + "where p.id = ? and s.id = ? and ps.id = ? "
                        + "and r.competition_type = 'LIVE_PAPER'::competition.competition_type "
                        + "and s.segment_type = 'OFFICIAL_EVALUATION' "
                        + "and s.end_event_sequence is not null and s.final_state_hash is not null "
                        + "and s.source_set_hash is not null and s.finalized_at is not null "
                        + "and ps.snapshot_type = 'LEADERBOARD_CUTOFF'::performance.snapshot_type "
                        + "and ps.evaluated_at = s.ends_at "
                        + "and ps.source_event_sequence = s.end_event_sequence",
                request.scoringTemplateVersionId(), request.participationId(), request.evaluationSegmentId(),
                request.performanceSnapshotId());

        if (evidence.isEmpty()) {
            throw new ScoringEvidenceNotFoundException();
        }
        if (evidence.size() != 1) {
            throw new IllegalStateException("Scoring evidence source is ambiguous");
        }
        return toSource(evidence.getFirst());
    }

    private static ScoringEvidenceSource toSource(Record record) {
        return new ScoringEvidenceSource(
                record.get("room_id", UUID.class),
                record.get("participation_id", UUID.class),
                record.get("bot_id", UUID.class),
                record.get("evaluation_segment_id", UUID.class),
                instant(record, "segment_starts_at"),
                instant(record, "segment_ends_at"),
                record.get("start_event_sequence", Long.class),
                record.get("end_event_sequence", Long.class),
                record.get("initial_state_hash", String.class),
                record.get("final_state_hash", String.class),
                record.get("source_set_hash", String.class),
                instant(record, "segment_finalized_at"),
                record.get("performance_snapshot_id", UUID.class),
                record.get("performance_source_sequence", Long.class),
                instant(record, "performance_evaluated_at"),
                record.get("performance_input_hash", String.class),
                record.get("performance_calculation_rules_version", String.class),
                record.get("performance_snapshot_hash", String.class),
                record.get("equity_amount", BigDecimal.class),
                record.get("total_return_pct", BigDecimal.class),
                record.get("max_drawdown_pct", BigDecimal.class),
                record.get("sharpe_ratio", BigDecimal.class),
                record.get("performance_metrics", String.class),
                record.get("room_rules_hash", String.class),
                record.get("scoring_parameters", String.class),
                record.get("locked_template_id", UUID.class),
                record.get("locked_template_rules_hash", String.class),
                record.get("calculation_template_id", UUID.class),
                record.get("calculation_template_code", String.class),
                record.get("calculation_template_version", String.class),
                record.get("calculation_template_rules", String.class),
                record.get("calculation_template_rules_hash", String.class));
    }

    private static java.time.Instant instant(Record record, String field) {
        return record.get(field, OffsetDateTime.class).toInstant();
    }
}

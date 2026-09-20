package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.RoomFinalizationCandidateSource;
import com.idea2strategy.backend.application.competition.RoomFinalizationSource;
import com.idea2strategy.backend.application.competition.RoomFinalizationWorkPort;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogRecord;
import com.idea2strategy.backend.application.competition.VirtualLiquidationRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RoomFinalizationWorkJooqAdapter implements RoomFinalizationWorkPort {
    private final DSLContext dsl;

    public RoomFinalizationWorkJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findDueRoomIds(Instant observedAt, int limit) {
        return dsl.fetch(
                        "select r.id from competition.rooms r "
                                + "join competition.room_schedules rs on rs.room_id = r.id "
                                + "where r.competition_type = 'LIVE_PAPER'::competition.competition_type "
                                + "and r.status = 'ENDED'::competition.room_status "
                                + "and rs.evaluation_ends_at <= ?::timestamptz "
                                + "and not exists (select 1 from competition.leaderboard_snapshots ls "
                                + "where ls.room_id = r.id and ls.status = 'FINAL'::competition.leaderboard_status) "
                                + "and exists (select 1 from competition.participations p where p.room_id = r.id "
                                + "and p.status in ('EVALUATING'::competition.participation_status, "
                                + "'COMPLETED'::competition.participation_status)) "
                                + "and not exists (select 1 from competition.participations p where p.room_id = r.id "
                                + "and p.status in ('REGISTERED'::competition.participation_status, "
                                + "'ACTIVE'::competition.participation_status, "
                                + "'PENDING_LEDGER'::competition.participation_status)) "
                                + "order by rs.evaluation_ends_at, r.id limit ?",
                        utc(observedAt), limit)
                .getValues("id", UUID.class);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VirtualLiquidationRequest> findPendingLiquidations(UUID roomId) {
        return dsl.fetch(
                        "select p.id as participation_id, s.id as segment_id "
                                + "from competition.participations p "
                                + "join competition.live_evaluation_segments s on s.participation_id = p.id "
                                + "where p.room_id = ? "
                                + "and p.status = 'EVALUATING'::competition.participation_status "
                                + "and s.segment_type = 'OFFICIAL_EVALUATION' and s.finalized_at is null "
                                + "order by p.id",
                        roomId)
                .map(row -> new VirtualLiquidationRequest(
                        row.get("participation_id", UUID.class), row.get("segment_id", UUID.class)));
    }

    @Override
    @Transactional
    public void markEvaluationCompleted(VirtualLiquidationRequest request, Instant completedAt) {
        OffsetDateTime completed = utc(completedAt);
        int updated = dsl.execute(
                "update competition.participations p "
                        + "set status = 'COMPLETED'::competition.participation_status, "
                        + "evaluation_finished_at = (select s.ends_at from competition.live_evaluation_segments s "
                        + "where s.id = ? and s.participation_id = p.id), evaluation_failure_code = null "
                        + "where p.id = ? and p.status = 'EVALUATING'::competition.participation_status "
                        + "and exists (select 1 from competition.live_evaluation_segments s "
                        + "join performance.bot_snapshots ps on ps.bot_id = p.bot_id "
                        + "and ps.snapshot_type = 'LEADERBOARD_CUTOFF'::performance.snapshot_type "
                        + "and ps.evaluated_at = s.ends_at and ps.source_event_sequence = s.end_event_sequence "
                        + "where s.id = ? and s.participation_id = p.id and s.finalized_at is not null)",
                request.evaluationSegmentId(), request.participationId(), request.evaluationSegmentId());
        if (updated == 0) {
            String status = (String) dsl.fetchValue(
                    "select status::text from competition.participations where id = ?",
                    request.participationId());
            if ("COMPLETED".equals(status)) {
                return;
            }
            throw new IllegalStateException("finalized evaluation could not become COMPLETED");
        }
        Number sequence = (Number) dsl.fetchValue(
                "select coalesce(max(event_sequence), 0) + 1 from competition.participation_events "
                        + "where participation_id = ?",
                request.participationId());
        UUID eventId = UUID.nameUUIDFromBytes(
                ("room-evaluation-completed.v1:" + request.participationId())
                        .getBytes(StandardCharsets.UTF_8));
        dsl.execute(
                "insert into competition.participation_events "
                        + "(id, participation_id, event_sequence, event_type, occurred_at, payload_document) "
                        + "values (?, ?, ?, 'EVALUATION_COMPLETED', ?::timestamptz, "
                        + "jsonb_build_object('evaluationSegmentId', ?::text)) on conflict (id) do nothing",
                eventId, request.participationId(), sequence.intValue(), completed,
                request.evaluationSegmentId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RoomFinalizationSource> loadReadyResult(UUID roomId) {
        Record room = dsl.fetchOne(
                "select r.id, rs.evaluation_ends_at, st.id as template_id, st.template_code, "
                        + "st.version, st.rules_document::text as rules_document, st.rules_hash, "
                        + "st.published_at, st.retired_at, "
                        + "(select count(*) from competition.participations p where p.room_id = r.id "
                        + "and p.status = 'COMPLETED'::competition.participation_status) "
                        + "as participation_count "
                        + "from competition.rooms r "
                        + "join competition.room_schedules rs on rs.room_id = r.id "
                        + "join competition.room_rules rr on rr.room_id = r.id "
                        + "join competition.scoring_template_versions st on st.id = rr.scoring_template_version_id "
                        + "where r.id = ? and r.competition_type = 'LIVE_PAPER'::competition.competition_type "
                        + "and r.status = 'ENDED'::competition.room_status "
                        + "and not exists (select 1 from competition.leaderboard_snapshots ls "
                        + "where ls.room_id = r.id and ls.status = 'FINAL'::competition.leaderboard_status)",
                roomId);
        if (room == null) {
            return Optional.empty();
        }
        var rows = dsl.fetch(
                "select p.id as participation_id, s.id as segment_id, ps.id as performance_id, "
                        + "extract(epoch from (s.ends_at - s.starts_at))::bigint as scheduled_seconds, "
                        + "extract(epoch from (s.ends_at - s.starts_at))::bigint as operation_seconds, "
                        + "(select count(*) from trading.fills f where f.bot_id = p.bot_id "
                        + "and f.occurred_at >= s.starts_at and f.occurred_at < s.ends_at)::int as fill_count, "
                        + "lrr.minimum_operation_seconds, lrr.minimum_fill_count "
                        + "from competition.participations p "
                        + "join competition.live_evaluation_segments s on s.participation_id = p.id "
                        + "join performance.bot_snapshots ps on ps.bot_id = p.bot_id "
                        + "and ps.snapshot_type = 'LEADERBOARD_CUTOFF'::performance.snapshot_type "
                        + "and ps.evaluated_at = s.ends_at and ps.source_event_sequence = s.end_event_sequence "
                        + "join competition.live_room_rules lrr on lrr.room_id = p.room_id "
                        + "where p.room_id = ? and p.status = 'COMPLETED'::competition.participation_status "
                        + "and s.segment_type = 'OFFICIAL_EVALUATION' and s.finalized_at is not null "
                        + "order by p.id",
                roomId);
        int participationCount = room.get("participation_count", Integer.class);
        if (rows.size() != participationCount || rows.isEmpty()) {
            return Optional.empty();
        }
        List<RoomFinalizationCandidateSource> candidates = rows.map(row ->
                new RoomFinalizationCandidateSource(
                        row.get("participation_id", UUID.class),
                        row.get("segment_id", UUID.class),
                        row.get("performance_id", UUID.class),
                        row.get("scheduled_seconds", Long.class),
                        row.get("operation_seconds", Long.class),
                        row.get("fill_count", Integer.class),
                        row.get("minimum_operation_seconds", Long.class),
                        row.get("minimum_fill_count", Integer.class)));
        OffsetDateTime retired = room.get("retired_at", OffsetDateTime.class);
        var template = new ScoringTemplateCatalogRecord(
                room.get("template_id", UUID.class),
                room.get("template_code", String.class),
                room.get("version", String.class),
                room.get("rules_document", String.class),
                room.get("rules_hash", String.class),
                room.get("published_at", OffsetDateTime.class).toInstant(),
                retired == null ? null : retired.toInstant());
        return Optional.of(new RoomFinalizationSource(
                roomId,
                room.get("evaluation_ends_at", OffsetDateTime.class).toInstant(),
                template,
                candidates));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.botcontrol.BotStopCommandPort;
import com.idea2strategy.backend.application.competition.PostEvaluationStopTransitionDecision;
import com.idea2strategy.backend.application.competition.PostEvaluationStopTransitionPort;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PostEvaluationStopTransitionJooqAdapter implements PostEvaluationStopTransitionPort {
    private static final String EVENT_TYPE = "POST_EVALUATION_STOP_DISPATCHED";
    private static final String REASON_CODE = "ROOM_EVALUATION_ENDED";
    private final DSLContext dsl;
    private final BotStopCommandPort stopPort;

    public PostEvaluationStopTransitionJooqAdapter(DSLContext dsl, BotStopCommandPort stopPort) {
        this.dsl = dsl;
        this.stopPort = stopPort;
    }

    @Override
    @Transactional
    public PostEvaluationStopTransitionDecision transitionNext(Instant observedAt) {
        Record candidate = dsl.fetchOne(
                "select p.id as participation_id, p.bot_id, p.owner_account_id, r.id as room_id, "
                        + "ls.id as final_snapshot_id "
                        + "from competition.participations p "
                        + "join competition.rooms r on r.id = p.room_id "
                        + "join bot.bots b on b.id = p.bot_id "
                        + "join competition.room_schedules rs on rs.room_id = r.id "
                        + "join competition.leaderboard_snapshots ls on ls.room_id = r.id "
                        + "and ls.status = 'FINAL'::competition.leaderboard_status "
                        + "and ls.cutoff_at = rs.evaluation_ends_at "
                        + "join competition.leaderboard_entries le on le.snapshot_id = ls.id "
                        + "and le.participation_id = p.id "
                        + "join performance.bot_snapshots ps on ps.id = le.performance_snapshot_id "
                        + "and ps.bot_id = p.bot_id and ps.snapshot_type = "
                        + "'LEADERBOARD_CUTOFF'::performance.snapshot_type "
                        + "where r.competition_type = 'LIVE_PAPER'::competition.competition_type "
                        + "and r.status = 'ENDED'::competition.room_status and r.ended_at is not null "
                        + "and p.status in ('COMPLETED'::competition.participation_status, "
                        + "'EVALUATION_FAILED'::competition.participation_status) "
                        + "and (p.post_room_action is null or "
                        + "p.post_room_action = 'STOP'::competition.post_room_action) "
                        + "and p.action_locked_at is not null "
                        + "and b.lifecycle_status = 'RUNNING'::bot.lifecycle_status "
                        + "and exists (select 1 from competition.live_evaluation_segments les "
                        + "where les.participation_id = p.id and les.segment_type = 'OFFICIAL_EVALUATION' "
                        + "and les.ends_at = ls.cutoff_at and les.finalized_at is not null "
                        + "and les.final_state_hash is not null and les.source_set_hash is not null "
                        + "and les.virtual_liquidation_document is not null) "
                        + "and not exists (select 1 from competition.participation_events pe "
                        + "where pe.participation_id = p.id and pe.event_type = ?) "
                        + "order by r.ended_at, p.id limit 1 for update of p skip locked",
                EVENT_TYPE);
        if (candidate == null) {
            return PostEvaluationStopTransitionDecision.NO_READY_CANDIDATE;
        }

        UUID botId = candidate.get("bot_id", UUID.class);
        var dispatch = stopPort.issueOwned(
                        botId, candidate.get("owner_account_id", UUID.class), REASON_CODE, observedAt)
                .orElseThrow(() -> new IllegalStateException("post-evaluation stop candidate lost bot ownership"));
        appendAuditEvent(candidate, dispatch.messageId(), dispatch.idempotencyKey(), observedAt);
        return PostEvaluationStopTransitionDecision.APPLIED;
    }

    private void appendAuditEvent(
            Record candidate, UUID messageId, String idempotencyKey, Instant observedAt) {
        UUID participationId = candidate.get("participation_id", UUID.class);
        int sequence = ((Number) dsl.fetchValue(
                "select coalesce(max(event_sequence), 0) + 1 from competition.participation_events "
                        + "where participation_id = ?",
                participationId)).intValue();
        dsl.execute(
                "insert into competition.participation_events "
                        + "(id, participation_id, event_sequence, event_type, reason_code, "
                        + "occurred_at, payload_document) values (?, ?, ?, ?, ?, ?::timestamptz, "
                        + "jsonb_build_object('roomId', ?::text, 'botId', ?::text, "
                        + "'finalSnapshotId', ?::text, 'stopMessageId', ?::text, "
                        + "'stopIdempotencyKey', ?))",
                derivedId(participationId), participationId, sequence, EVENT_TYPE, REASON_CODE,
                utc(observedAt), candidate.get("room_id", UUID.class), candidate.get("bot_id", UUID.class),
                candidate.get("final_snapshot_id", UUID.class), messageId, idempotencyKey);
    }

    private static UUID derivedId(UUID participationId) {
        return UUID.nameUUIDFromBytes(
                (EVENT_TYPE + ":" + participationId).getBytes(StandardCharsets.UTF_8));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

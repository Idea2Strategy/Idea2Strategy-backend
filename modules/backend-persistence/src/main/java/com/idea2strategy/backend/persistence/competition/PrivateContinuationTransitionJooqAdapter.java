package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.PrivateContinuationTransitionConflictException;
import com.idea2strategy.backend.application.competition.PrivateContinuationTransitionDecision;
import com.idea2strategy.backend.application.competition.PrivateContinuationTransitionPort;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PrivateContinuationTransitionJooqAdapter implements PrivateContinuationTransitionPort {
    private static final Duration INITIAL_CONTINUATION = Duration.ofDays(30);
    private static final String EVENT_TYPE = "PRIVATE_CONTINUATION_ACTIVATED";
    private static final String NOTIFICATION_TYPE = "PRIVATE_CONTINUATION_ACTIVATED_NOTIFICATION";
    private final DSLContext dsl;

    public PrivateContinuationTransitionJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional
    public PrivateContinuationTransitionDecision transitionNext(Instant observedAt) {
        Record candidate = dsl.fetchOne(
                "select p.id as participation_id, p.bot_id, p.owner_account_id, r.id as room_id, "
                        + "r.ended_at, ls.id as final_snapshot_id "
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
                        + "and p.post_room_action = 'CONTINUE_PRIVATE'::competition.post_room_action "
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
            return PrivateContinuationTransitionDecision.NO_READY_CANDIDATE;
        }

        UUID participationId = candidate.get("participation_id", UUID.class);
        UUID botId = candidate.get("bot_id", UUID.class);
        dsl.fetchOne("select pg_advisory_xact_lock(hashtextextended(?::text, 0))", botId);
        Record bot = dsl.fetchOne(
                "select lifecycle_status::text as lifecycle_status from bot.bots where id = ? for update",
                botId);
        String lifecycle = bot == null ? null : bot.get("lifecycle_status", String.class);
        if (!"RUNNING".equals(lifecycle)) {
            return PrivateContinuationTransitionDecision.NO_READY_CANDIDATE;
        }

        Instant expectedDueAt = candidate.get("ended_at", OffsetDateTime.class)
                .toInstant().plus(INITIAL_CONTINUATION);
        Record deadline = dsl.fetchOne(
                "select due_at, last_renewed_at, renewal_sequence from bot.continuation_deadlines "
                        + "where bot_id = ? for update",
                botId);
        Instant dueAt = expectedDueAt;
        if (deadline == null) {
            dsl.execute(
                    "insert into bot.continuation_deadlines "
                            + "(bot_id, due_at, renewal_sequence, created_at, updated_at) "
                            + "values (?, ?::timestamptz, 0, ?::timestamptz, ?::timestamptz)",
                    botId, utc(expectedDueAt), utc(observedAt), utc(observedAt));
        } else {
            dueAt = deadline.get("due_at", OffsetDateTime.class).toInstant();
            long sequence = ((Number) deadline.get("renewal_sequence")).longValue();
            boolean renewed = sequence > 0 || deadline.get("last_renewed_at", OffsetDateTime.class) != null;
            if (!dueAt.equals(expectedDueAt) && !renewed) {
                throw new PrivateContinuationTransitionConflictException(
                        "an unrenewed continuation deadline conflicts with the official room end");
            }
            if (renewed && dueAt.isBefore(expectedDueAt)) {
                throw new PrivateContinuationTransitionConflictException(
                        "a renewed continuation deadline cannot precede the initial room deadline");
            }
        }

        appendParticipationEvent(candidate, dueAt, observedAt);
        appendNotification(candidate, dueAt, observedAt);
        return PrivateContinuationTransitionDecision.APPLIED;
    }

    private void appendParticipationEvent(Record candidate, Instant dueAt, Instant observedAt) {
        UUID participationId = candidate.get("participation_id", UUID.class);
        int sequence = ((Number) dsl.fetchValue(
                "select coalesce(max(event_sequence), 0) + 1 from competition.participation_events "
                        + "where participation_id = ?",
                participationId)).intValue();
        dsl.execute(
                "insert into competition.participation_events "
                        + "(id, participation_id, event_sequence, event_type, occurred_at, payload_document) "
                        + "values (?, ?, ?, ?, ?::timestamptz, jsonb_build_object("
                        + "'roomId', ?::text, 'botId', ?::text, 'finalSnapshotId', ?::text, "
                        + "'continuationDeadline', ?::text))",
                derivedId("event", participationId.toString()), participationId, sequence, EVENT_TYPE,
                utc(observedAt), candidate.get("room_id", UUID.class), candidate.get("bot_id", UUID.class),
                candidate.get("final_snapshot_id", UUID.class), dueAt.toString());
    }

    private void appendNotification(Record candidate, Instant dueAt, Instant observedAt) {
        UUID participationId = candidate.get("participation_id", UUID.class);
        UUID ownerAccountId = candidate.get("owner_account_id", UUID.class);
        String idempotencyKey = "private-continuation-activated:" + participationId;
        UUID messageId = derivedId("notification", idempotencyKey);
        long sequence = ((Number) dsl.fetchValue(
                "select coalesce(max(aggregate_sequence), 0) + 1 from operations.outbox_messages "
                        + "where owner_domain = 'competition' and aggregate_id = ?",
                participationId)).longValue();
        dsl.execute(
                "insert into operations.outbox_messages "
                        + "(id, owner_domain, aggregate_id, aggregate_sequence, event_type, event_schema_version, "
                        + "payload_document, idempotency_key, created_at) values "
                        + "(?, 'competition', ?, ?, ?, 'competition-room.v1', jsonb_build_object("
                        + "'metadata', jsonb_build_object('contractVersion', 'competition-room.v1', "
                        + "'messageType', ?, 'messageId', ?::text, 'occurredAt', ?::text, "
                        + "'idempotencyKey', ?), 'roomId', ?::text, 'participationId', ?::text, "
                        + "'botId', ?::text, 'ownerAccountId', ?::text, "
                        + "'channels', jsonb_build_array('IN_APP', 'EMAIL'), "
                        + "'continuationDeadline', ?::text), ?, ?::timestamptz) "
                        + "on conflict (idempotency_key) do nothing",
                messageId, participationId, sequence, NOTIFICATION_TYPE, NOTIFICATION_TYPE, messageId,
                observedAt.toString(), idempotencyKey, candidate.get("room_id", UUID.class), participationId,
                candidate.get("bot_id", UUID.class), ownerAccountId, dueAt.toString(), idempotencyKey,
                utc(observedAt));
    }

    private static UUID derivedId(String kind, String material) {
        return UUID.nameUUIDFromBytes((kind + ":" + material).getBytes(StandardCharsets.UTF_8));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

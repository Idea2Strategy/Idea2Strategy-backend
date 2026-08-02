package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.botcontrol.BotRunCommandPort;
import com.idea2strategy.backend.application.competition.RoomScheduleTransitionPort;
import com.idea2strategy.backend.application.competition.RoomScheduleTransitionReport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RoomScheduleTransitionJooqAdapter implements RoomScheduleTransitionPort {
    private static final String INSUFFICIENT_PARTICIPATION = "INSUFFICIENT_PARTICIPATION";
    private static final Duration PRIVATE_CONTINUATION_PERIOD = Duration.ofDays(30);
    private final DSLContext dsl;
    private final ObjectProvider<BotRunCommandPort> runPortProvider;

    public RoomScheduleTransitionJooqAdapter(
            DSLContext dsl, ObjectProvider<BotRunCommandPort> runPortProvider) {
        this.dsl = dsl;
        this.runPortProvider = runPortProvider;
    }

    @Override
    @Transactional
    public RoomScheduleTransitionReport advanceDue(Instant observedAt, int limit) {
        OffsetDateTime observed = utc(observedAt);
        var candidates = dsl.fetch(
                "select r.id, r.status::text as status, s.recruitment_opens_at, "
                        + "s.evaluation_starts_at, s.participation_closes_at, s.evaluation_ends_at "
                        + "from competition.rooms r "
                        + "join competition.room_schedules s on s.room_id = r.id "
                        + "where (r.status = 'DRAFT'::competition.room_status and s.recruitment_opens_at <= ?::timestamptz) "
                        + "or (r.status = 'RECRUITING'::competition.room_status and s.evaluation_starts_at <= ?::timestamptz) "
                        + "or (r.status = 'EVALUATING'::competition.room_status and "
                        + "(s.participation_closes_at <= ?::timestamptz or s.evaluation_ends_at <= ?::timestamptz)) "
                        + "order by case r.status::text when 'DRAFT' then s.recruitment_opens_at "
                        + "when 'RECRUITING' then s.evaluation_starts_at else s.evaluation_ends_at end, r.id "
                        + "limit ? for update of r skip locked",
                observed, observed, observed, observed, limit);

        int transitions = 0;
        for (var candidate : candidates) {
            UUID roomId = candidate.get("id", UUID.class);
            String status = candidate.get("status", String.class);
            OffsetDateTime recruitmentOpensAt = candidate.get("recruitment_opens_at", OffsetDateTime.class);
            OffsetDateTime evaluationStartsAt = candidate.get("evaluation_starts_at", OffsetDateTime.class);
            OffsetDateTime participationClosesAt = candidate.get("participation_closes_at", OffsetDateTime.class);
            OffsetDateTime evaluationEndsAt = candidate.get("evaluation_ends_at", OffsetDateTime.class);
            if ("DRAFT".equals(status) && !observed.isBefore(recruitmentOpensAt)) {
                transition(roomId, "DRAFT", "RECRUITING", "RECRUITMENT_OPENED", recruitmentOpensAt, observed);
                status = "RECRUITING";
                transitions++;
            }
            if ("RECRUITING".equals(status) && !observed.isBefore(evaluationStartsAt)) {
                if (!observed.isBefore(participationClosesAt)
                        && endIfInsufficient(roomId, "RECRUITING", participationClosesAt, observed)) {
                    transitions++;
                    continue;
                }
                transition(roomId, "RECRUITING", "EVALUATING", "EVALUATION_STARTED", evaluationStartsAt, observed);
                status = "EVALUATING";
                transitions++;
            }
            if ("EVALUATING".equals(status)
                    && !observed.isBefore(participationClosesAt)
                    && endIfInsufficient(roomId, "EVALUATING", participationClosesAt, observed)) {
                transitions++;
                continue;
            }
            if ("EVALUATING".equals(status) && !observed.isBefore(evaluationEndsAt)) {
                transition(roomId, "EVALUATING", "ENDED", "EVALUATION_ENDED", evaluationEndsAt, observed);
                transitions++;
            }
        }
        return new RoomScheduleTransitionReport(observedAt, candidates.size(), transitions);
    }

    private boolean endIfInsufficient(
            UUID roomId, String expectedStatus, OffsetDateTime scheduledAt, OffsetDateTime observedAt) {
        var participations = dsl.fetch(
                "select p.id, p.bot_id, p.owner_account_id, p.status::text as status, "
                        + "b.lifecycle_status::text as lifecycle_status "
                        + "from competition.participations p join bot.bots b on b.id = p.bot_id "
                        + "where p.room_id = ? and p.status in "
                        + "('REGISTERED'::competition.participation_status, "
                        + "'EVALUATING'::competition.participation_status) "
                        + "order by p.id for update of p, b",
                roomId);
        if (participations.size() >= 2) {
            return false;
        }

        int updated = dsl.execute(
                "update competition.rooms set status = 'ENDED'::competition.room_status, "
                        + "ended_at = ?::timestamptz where id = ? and status = ?::competition.room_status",
                observedAt, roomId, expectedStatus);
        if (updated != 1) {
            throw new IllegalStateException("Insufficient-participation transition lost its locked state");
        }
        roomEvent(roomId, INSUFFICIENT_PARTICIPATION, "ENDED", INSUFFICIENT_PARTICIPATION,
                scheduledAt, observedAt);

        for (var participation : participations) {
            UUID participationId = participation.get("id", UUID.class);
            UUID botId = participation.get("bot_id", UUID.class);
            UUID ownerAccountId = participation.get("owner_account_id", UUID.class);
            String participationStatus = participation.get("status", String.class);
            dsl.execute(
                    "update competition.participations set status = 'WITHDRAWN'::competition.participation_status, "
                            + "withdrawn_at = ?::timestamptz, withdrawal_reason_code = ? where id = ?",
                    observedAt, INSUFFICIENT_PARTICIPATION, participationId);
            participationEvent(participationId, roomId, botId, observedAt);
            if ("RUNNING".equals(participation.get("lifecycle_status", String.class))) {
                continuePrivately(botId, ownerAccountId, participationStatus, observedAt);
            }
        }
        insufficientParticipationNotifications(roomId, observedAt);
        return true;
    }

    private void continuePrivately(
            UUID botId, UUID ownerAccountId, String participationStatus, OffsetDateTime observedAt) {
        Instant occurredAt = observedAt.toInstant();
        if ("REGISTERED".equals(participationStatus)) {
            dsl.execute(
                    "update bot.bots set execution_eligible_from = ?::timestamptz, updated_at = ?::timestamptz "
                            + "where id = ?",
                    observedAt, observedAt, botId);
            runPortProvider.getObject().issueOwned(botId, ownerAccountId, occurredAt)
                    .orElseThrow(() -> new IllegalStateException("Detached room bot owner could not be resolved"));
            return;
        }
        dsl.execute(
                "insert into bot.continuation_deadlines "
                        + "(bot_id, due_at, renewal_sequence, created_at, updated_at) "
                        + "values (?, ?::timestamptz, 0, ?::timestamptz, ?::timestamptz) "
                        + "on conflict (bot_id) do update set due_at = excluded.due_at, "
                        + "renewal_sequence = 0, updated_at = excluded.updated_at",
                botId, utc(occurredAt.plus(PRIVATE_CONTINUATION_PERIOD)), observedAt, observedAt);
    }

    private void participationEvent(
            UUID participationId, UUID roomId, UUID botId, OffsetDateTime observedAt) {
        int sequence = ((Number) dsl.fetchValue(
                "select coalesce(max(event_sequence), 0) + 1 from competition.participation_events "
                        + "where participation_id = ?",
                participationId)).intValue();
        dsl.execute(
                "insert into competition.participation_events "
                        + "(id, participation_id, event_sequence, event_type, reason_code, occurred_at, payload_document) "
                        + "values (?, ?, ?, 'ROOM_ENDED_INSUFFICIENT_PARTICIPATION', ?, ?::timestamptz, "
                        + "jsonb_build_object('roomId', ?::text, 'botId', ?::text, 'reasonCode', ?))",
                UUID.randomUUID(), participationId, sequence, INSUFFICIENT_PARTICIPATION, observedAt,
                roomId, botId, INSUFFICIENT_PARTICIPATION);
    }

    private void insufficientParticipationNotifications(UUID roomId, OffsetDateTime observedAt) {
        var room = dsl.fetchOne(
                "select creator_account_id, created_by_operator_id from competition.rooms where id = ?",
                roomId);
        Map<UUID, String> recipients = new LinkedHashMap<>();
        if (room.get("creator_account_id", UUID.class) != null) {
            recipients.put(room.get("creator_account_id", UUID.class), "ACCOUNT");
        }
        if (room.get("created_by_operator_id", UUID.class) != null) {
            recipients.put(room.get("created_by_operator_id", UUID.class), "OPERATOR");
        }
        dsl.fetch("select distinct owner_account_id from competition.participations "
                        + "where room_id = ? and withdrawal_reason_code = ? and withdrawn_at = ?::timestamptz",
                        roomId, INSUFFICIENT_PARTICIPATION, observedAt)
                .forEach(row -> recipients.put(row.get("owner_account_id", UUID.class), "ACCOUNT"));

        for (var recipient : recipients.entrySet()) {
            String idempotencyKey = "room-insufficient-participation:" + roomId + ":"
                    + recipient.getValue() + ":" + recipient.getKey();
            UUID messageId = derivedId("notification", idempotencyKey);
            long sequence = ((Number) dsl.fetchValue(
                    "select coalesce(max(aggregate_sequence), 0) + 1 from operations.outbox_messages "
                            + "where owner_domain = 'competition' and aggregate_id = ?",
                    roomId)).longValue();
            dsl.execute(
                    "insert into operations.outbox_messages "
                            + "(id, owner_domain, aggregate_id, aggregate_sequence, event_type, event_schema_version, "
                            + "payload_document, idempotency_key, created_at) "
                            + "values (?, 'competition', ?, ?, "
                            + "'ROOM_INSUFFICIENT_PARTICIPATION_ENDED_NOTIFICATION', 'competition-room.v1', "
                            + "jsonb_build_object("
                            + "'metadata', jsonb_build_object('contractVersion', 'competition-room.v1', "
                            + "'messageType', 'ROOM_INSUFFICIENT_PARTICIPATION_ENDED_NOTIFICATION', "
                            + "'messageId', ?::text, 'occurredAt', ?::text, 'idempotencyKey', ?), "
                            + "'roomId', ?::text, 'recipientId', ?::text, 'recipientType', ?, "
                            + "'reasonCode', ?, 'resultGenerated', false, "
                            + "'botTransitions', coalesce((select jsonb_agg(jsonb_build_object("
                            + "'botId', p.bot_id::text, 'continuedPrivately', "
                            + "(b.lifecycle_status = 'RUNNING'::bot.lifecycle_status), "
                            + "'continuationDeadline', d.due_at::text) order by p.bot_id) "
                            + "from competition.participations p join bot.bots b on b.id = p.bot_id "
                            + "left join bot.continuation_deadlines d on d.bot_id = p.bot_id "
                            + "where p.room_id = ? and p.owner_account_id = ? "
                            + "and p.withdrawal_reason_code = ? and p.withdrawn_at = ?::timestamptz), '[]'::jsonb)), "
                            + "?, ?::timestamptz) on conflict (idempotency_key) do nothing",
                    messageId, roomId, sequence, messageId, observedAt.toInstant().toString(), idempotencyKey,
                    roomId, recipient.getKey(), recipient.getValue(), INSUFFICIENT_PARTICIPATION,
                    roomId, recipient.getKey(), INSUFFICIENT_PARTICIPATION, observedAt,
                    idempotencyKey, observedAt);
        }
    }

    private void transition(
            UUID roomId,
            String expectedStatus,
            String resultingStatus,
            String eventType,
            OffsetDateTime scheduledAt,
            OffsetDateTime observedAt) {
        int updated;
        if ("ENDED".equals(resultingStatus)) {
            updated = dsl.execute(
                    "update competition.rooms set status = ?::competition.room_status, ended_at = ?::timestamptz "
                            + "where id = ? and status = ?::competition.room_status",
                    resultingStatus, observedAt, roomId, expectedStatus);
        } else {
            updated = dsl.execute(
                    "update competition.rooms set status = ?::competition.room_status "
                            + "where id = ? and status = ?::competition.room_status",
                    resultingStatus, roomId, expectedStatus);
        }
        if (updated != 1) {
            throw new IllegalStateException("Room schedule transition lost its locked state");
        }
        roomEvent(roomId, eventType, resultingStatus, null, scheduledAt, observedAt);
    }

    private void roomEvent(
            UUID roomId, String eventType, String resultingStatus, String reasonCode,
            OffsetDateTime scheduledAt, OffsetDateTime observedAt) {
        Number nextSequence = (Number) dsl.fetchValue(
                "select coalesce(max(event_sequence), 0) + 1 from competition.room_events where room_id = ?",
                roomId);
        dsl.execute(
                "insert into competition.room_events "
                        + "(id, room_id, event_sequence, event_type, resulting_status, reason_code, occurred_at, "
                        + "payload_document) values (?, ?, ?, ?, ?::competition.room_status, ?::varchar, ?::timestamptz, "
                        + "jsonb_build_object('scheduledAt', ?::text, 'observedAt', ?::text, "
                        + "'reasonCode', ?::varchar))",
                UUID.randomUUID(), roomId, nextSequence.intValue(), eventType, resultingStatus, reasonCode, observedAt,
                scheduledAt.toInstant().toString(), observedAt.toInstant().toString(), reasonCode);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static UUID derivedId(String kind, String material) {
        return UUID.nameUUIDFromBytes((kind + ":" + material).getBytes(StandardCharsets.UTF_8));
    }
}

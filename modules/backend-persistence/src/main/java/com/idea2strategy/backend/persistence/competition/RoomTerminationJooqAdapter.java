package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.botcontrol.BotRunCommandPort;
import com.idea2strategy.backend.application.botcontrol.BotStopCommandPort;
import com.idea2strategy.backend.application.competition.ParticipationExitAction;
import com.idea2strategy.backend.application.competition.RoomTerminationAccessException;
import com.idea2strategy.backend.application.competition.RoomTerminationConflictException;
import com.idea2strategy.backend.application.competition.RoomTerminationPort;
import com.idea2strategy.backend.application.competition.RoomTerminationResult;
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
public class RoomTerminationJooqAdapter implements RoomTerminationPort {
    private static final Duration PRIVATE_CONTINUATION_PERIOD = Duration.ofDays(30);
    private final DSLContext dsl;
    private final BotRunCommandPort runPort;
    private final BotStopCommandPort stopPort;

    public RoomTerminationJooqAdapter(DSLContext dsl, BotRunCommandPort runPort, BotStopCommandPort stopPort) {
        this.dsl = dsl;
        this.runPort = runPort;
        this.stopPort = stopPort;
    }

    @Override
    @Transactional
    public RoomTerminationResult withdrawOwned(
            UUID roomId, UUID participationId, UUID ownerAccountId, ParticipationExitAction action,
            String reasonCode, Instant occurredAt) {
        String roomStatus = (String) dsl.fetchValue(
                "select status::text from competition.rooms where id = ? for update", roomId);
        if (!"RECRUITING".equals(roomStatus) && !"EVALUATING".equals(roomStatus)) {
            throw new RoomTerminationAccessException();
        }
        Record participation = dsl.fetchOne(
                "select p.bot_id, p.status::text as status, b.lifecycle_status::text as bot_status "
                        + "from competition.participations p join bot.bots b on b.id = p.bot_id "
                        + "where p.id = ? and p.room_id = ? and p.owner_account_id = ? "
                        + "for update of p, b",
                participationId, roomId, ownerAccountId);
        if (participation == null) {
            throw new RoomTerminationAccessException();
        }
        String status = participation.get("status", String.class);
        if (!"REGISTERED".equals(status) && !"EVALUATING".equals(status)) {
            throw new RoomTerminationConflictException("Only an active participation can be withdrawn");
        }
        UUID botId = participation.get("bot_id", UUID.class);
        withdraw(participationId, reasonCode, "PARTICIPATION_WITHDRAWN", occurredAt);
        if (action == ParticipationExitAction.STOP) {
            stopPort.issueOwned(botId, ownerAccountId, "ROOM_WITHDRAWAL", occurredAt)
                    .orElseThrow(RoomTerminationAccessException::new);
        } else {
            continuePrivate(botId, ownerAccountId, status, occurredAt);
        }
        return new RoomTerminationResult(roomId, 1, occurredAt);
    }

    @Override
    @Transactional
    public RoomTerminationResult cancelOwned(
            UUID roomId, UUID creatorAccountId, String reasonCode, Instant occurredAt) {
        Record room = dsl.fetchOne(
                "select r.status::text as status, s.participation_opens_at "
                        + "from competition.rooms r join competition.room_schedules s on s.room_id = r.id "
                        + "where r.id = ? and r.organizer_type = 'USER'::competition.organizer_type "
                        + "and r.creator_account_id = ? for update of r",
                roomId, creatorAccountId);
        if (room == null) {
            throw new RoomTerminationAccessException();
        }
        String status = room.get("status", String.class);
        Instant submissionOpensAt = room.get("participation_opens_at", OffsetDateTime.class).toInstant();
        if (!("DRAFT".equals(status) || "RECRUITING".equals(status))
                || !occurredAt.isBefore(submissionOpensAt)) {
            throw new RoomTerminationConflictException("A creator can cancel only before submission opens");
        }
        dsl.execute(
                "update competition.rooms set status = 'CANCELLED'::competition.room_status, "
                        + "ended_at = ?::timestamptz where id = ?",
                utc(occurredAt), roomId);
        roomEvent(roomId, "ROOM_CANCELLED", "CANCELLED", reasonCode, creatorAccountId, occurredAt);
        int count = detachRoomParticipations(roomId, reasonCode, "ROOM_CANCELLED", false, occurredAt);
        return new RoomTerminationResult(roomId, count, occurredAt);
    }

    @Override
    @Transactional
    public RoomTerminationResult invalidate(
            UUID roomId, UUID operatorId, String reasonCode, Instant occurredAt) {
        boolean activeOperator = Boolean.TRUE.equals(dsl.fetchValue(
                "select exists(select 1 from operations.operator_accounts where id = ? and status = 'ACTIVE')",
                operatorId));
        if (!activeOperator) {
            throw new RoomTerminationAccessException();
        }
        Record room = dsl.fetchOne(
                "select r.status::text as status, s.participation_opens_at "
                        + "from competition.rooms r join competition.room_schedules s on s.room_id = r.id "
                        + "where r.id = ? for update of r",
                roomId);
        if (room == null) {
            throw new RoomTerminationAccessException();
        }
        String status = room.get("status", String.class);
        Instant submissionOpensAt = room.get("participation_opens_at", OffsetDateTime.class).toInstant();
        if (!("RECRUITING".equals(status) || "EVALUATING".equals(status))
                || occurredAt.isBefore(submissionOpensAt)) {
            throw new RoomTerminationConflictException(
                    "A platform invalidation requires a submitted room that is still in progress");
        }
        dsl.execute(
                "update competition.rooms set status = 'INVALIDATED'::competition.room_status, "
                        + "ended_at = ?::timestamptz, invalidated_at = ?::timestamptz, "
                        + "invalidation_reason_code = ? where id = ?",
                utc(occurredAt), utc(occurredAt), reasonCode, roomId);
        roomEvent(roomId, "ROOM_INVALIDATED", "INVALIDATED", reasonCode, operatorId, occurredAt);
        int count = detachRoomParticipations(roomId, reasonCode, "ROOM_INVALIDATED", true, occurredAt);
        return new RoomTerminationResult(roomId, count, occurredAt);
    }

    private int detachRoomParticipations(
            UUID roomId, String reasonCode, String eventType, boolean invalidateEvaluation, Instant occurredAt) {
        var participations = dsl.fetch(
                "select p.id, p.bot_id, p.owner_account_id, p.status::text as status "
                        + "from competition.participations p join bot.bots b on b.id = p.bot_id "
                        + "where p.room_id = ? and p.status in ('REGISTERED'::competition.participation_status, "
                        + "'EVALUATING'::competition.participation_status) order by p.id for update of p, b",
                roomId);
        for (Record participation : participations) {
            UUID participationId = participation.get("id", UUID.class);
            String status = participation.get("status", String.class);
            if (invalidateEvaluation && "EVALUATING".equals(status)) {
                dsl.execute(
                        "update competition.participations set status = "
                                + "'EVALUATION_FAILED'::competition.participation_status, "
                                + "evaluation_finished_at = ?::timestamptz, evaluation_failure_code = ? where id = ?",
                        utc(occurredAt), reasonCode, participationId);
                participationEvent(participationId, eventType, reasonCode, occurredAt);
            } else {
                withdraw(participationId, reasonCode, eventType, occurredAt);
            }
            UUID botId = participation.get("bot_id", UUID.class);
            if (isRunning(botId)) {
                continuePrivate(botId, participation.get("owner_account_id", UUID.class), status, occurredAt);
            }
        }
        return participations.size();
    }

    private void withdraw(UUID participationId, String reasonCode, String eventType, Instant occurredAt) {
        dsl.execute(
                "update competition.participations set status = 'WITHDRAWN'::competition.participation_status, "
                        + "withdrawn_at = ?::timestamptz, withdrawal_reason_code = ? where id = ?",
                utc(occurredAt), reasonCode, participationId);
        participationEvent(participationId, eventType, reasonCode, occurredAt);
    }

    private void continuePrivate(UUID botId, UUID ownerAccountId, String priorStatus, Instant occurredAt) {
        if (!isRunning(botId)) {
            throw new RoomTerminationConflictException("Only a running room bot can continue privately");
        }
        if ("REGISTERED".equals(priorStatus)) {
            dsl.execute(
                    "update bot.bots set execution_eligible_from = ?::timestamptz, updated_at = ?::timestamptz where id = ?",
                    utc(occurredAt), utc(occurredAt), botId);
            runPort.issueOwned(botId, ownerAccountId, occurredAt)
                    .orElseThrow(RoomTerminationAccessException::new);
        } else {
            dsl.execute(
                    "insert into bot.continuation_deadlines "
                            + "(bot_id, due_at, renewal_sequence, created_at, updated_at) "
                            + "values (?, ?::timestamptz, 0, ?::timestamptz, ?::timestamptz) "
                            + "on conflict (bot_id) do update set due_at = excluded.due_at, "
                            + "renewal_sequence = 0, updated_at = excluded.updated_at",
                    botId, utc(occurredAt.plus(PRIVATE_CONTINUATION_PERIOD)), utc(occurredAt), utc(occurredAt));
        }
    }

    private boolean isRunning(UUID botId) {
        return "RUNNING".equals(dsl.fetchValue(
                "select lifecycle_status::text from bot.bots where id = ?", botId));
    }

    private void participationEvent(
            UUID participationId, String eventType, String reasonCode, Instant occurredAt) {
        int sequence = ((Number) dsl.fetchValue(
                "select coalesce(max(event_sequence), 0) + 1 from competition.participation_events "
                        + "where participation_id = ?", participationId)).intValue();
        dsl.execute(
                "insert into competition.participation_events "
                        + "(id, participation_id, event_sequence, event_type, reason_code, occurred_at, payload_document) "
                        + "values (?, ?, ?, ?, ?, ?::timestamptz, jsonb_build_object('reasonCode', ?))",
                UUID.randomUUID(), participationId, sequence, eventType, reasonCode, utc(occurredAt), reasonCode);
    }

    private void roomEvent(
            UUID roomId, String eventType, String status, String reasonCode, UUID actorId, Instant occurredAt) {
        int sequence = ((Number) dsl.fetchValue(
                "select coalesce(max(event_sequence), 0) + 1 from competition.room_events where room_id = ?",
                roomId)).intValue();
        dsl.execute(
                "insert into competition.room_events "
                        + "(id, room_id, event_sequence, event_type, resulting_status, reason_code, occurred_at, payload_document) "
                        + "values (?, ?, ?, ?, ?::competition.room_status, ?, ?::timestamptz, "
                        + "jsonb_build_object('actorId', ?::text, 'reasonCode', ?))",
                UUID.randomUUID(), roomId, sequence, eventType, status, reasonCode, utc(occurredAt), actorId, reasonCode);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

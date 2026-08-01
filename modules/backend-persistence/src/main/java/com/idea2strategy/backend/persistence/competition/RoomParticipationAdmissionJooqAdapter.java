package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.RoomBotProvisioningAction;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmission;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionContext;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionFailure;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionOutcome;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionPort;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

@Repository
public class RoomParticipationAdmissionJooqAdapter implements RoomParticipationAdmissionPort {
    private static final int ACCOUNT_EXECUTION_LIMIT = 10;

    private final DSLContext dsl;

    public RoomParticipationAdmissionJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional
    public RoomParticipationAdmissionOutcome admit(
            RoomParticipationAdmissionRequest request, RoomBotProvisioningAction provisioningAction) {
        dsl.fetchOne(
                "select pg_advisory_xact_lock(hashtextextended(?::text, 0))",
                request.ownerAccountId());
        Object accountStatus = dsl.fetchValue(
                "select lifecycle_status::text from identity.accounts where id = ? for update",
                request.ownerAccountId());
        if (!"ACTIVE".equals(accountStatus)) {
            return rejected(RoomParticipationAdmissionFailure.ACCOUNT_INELIGIBLE);
        }

        Record room = dsl.fetchOne(
                "select r.competition_type::text as competition_type, r.status::text as room_status, "
                        + "rules.bot_participation_limit, rules.per_account_bot_limit, "
                        + "schedule.recruitment_opens_at, schedule.participation_closes_at, "
                        + "schedule.evaluation_starts_at "
                        + "from competition.rooms r "
                        + "join competition.room_rules rules on rules.room_id = r.id "
                        + "join competition.room_schedules schedule on schedule.room_id = r.id "
                        + "where r.id = ? for update of r",
                request.roomId());
        if (!joinable(room, request.admittedAt().atOffset(ZoneOffset.UTC))) {
            return rejected(RoomParticipationAdmissionFailure.ROOM_NOT_JOINABLE);
        }

        int roomCount = countOccupied(request.roomId(), null);
        if (roomCount >= room.get("bot_participation_limit", Integer.class)) {
            return rejected(RoomParticipationAdmissionFailure.ROOM_CAPACITY_REACHED);
        }
        int accountRoomCount = countOccupied(request.roomId(), request.ownerAccountId());
        if (accountRoomCount >= room.get("per_account_bot_limit", Integer.class)) {
            return rejected(RoomParticipationAdmissionFailure.ACCOUNT_ROOM_LIMIT_REACHED);
        }
        if (aliasExists(request.roomId(), request.anonymousAlias())) {
            return rejected(RoomParticipationAdmissionFailure.ANONYMOUS_ALIAS_CONFLICT);
        }
        if (projectedExecutionCount(request.ownerAccountId()) >= ACCOUNT_EXECUTION_LIMIT) {
            return rejected(RoomParticipationAdmissionFailure.ACCOUNT_EXECUTION_LIMIT_REACHED);
        }

        OffsetDateTime executionEligibleFrom = room.get("evaluation_starts_at", OffsetDateTime.class);
        var context = new RoomParticipationAdmissionContext(
                request.roomId(),
                request.ownerAccountId(),
                request.admittedAt(),
                executionEligibleFrom.toInstant());
        UUID botId = provisioningAction.provision(context);
        if (!validProvisionedBot(
                botId,
                request.ownerAccountId(),
                request.admittedAt().atOffset(ZoneOffset.UTC),
                executionEligibleFrom)) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return rejected(RoomParticipationAdmissionFailure.PROVISIONED_BOT_INVALID);
        }

        OffsetDateTime admittedAt = request.admittedAt().atOffset(ZoneOffset.UTC);
        dsl.execute(
                "insert into competition.participations "
                        + "(id, room_id, bot_id, owner_account_id, anonymous_alias, status, joined_at) "
                        + "values (?, ?, ?, ?, ?, 'REGISTERED'::competition.participation_status, ?::timestamptz)",
                request.participationId(),
                request.roomId(),
                botId,
                request.ownerAccountId(),
                request.anonymousAlias(),
                admittedAt);
        dsl.execute(
                "insert into competition.participation_events "
                        + "(id, participation_id, event_sequence, event_type, occurred_at, payload_document) "
                        + "values (?, ?, 1, 'PARTICIPATION_REGISTERED', ?::timestamptz, "
                        + "'{\"status\":\"REGISTERED\"}'::jsonb)",
                request.eventId(),
                request.participationId(),
                admittedAt);
        return RoomParticipationAdmissionOutcome.accepted(new RoomParticipationAdmission(
                request.participationId(),
                request.roomId(),
                botId,
                request.ownerAccountId(),
                request.anonymousAlias(),
                request.admittedAt()));
    }

    private static boolean joinable(Record room, OffsetDateTime at) {
        if (room == null
                || !"LIVE_PAPER".equals(room.get("competition_type", String.class))
                || !"RECRUITING".equals(room.get("room_status", String.class))) {
            return false;
        }
        OffsetDateTime opensAt = room.get("recruitment_opens_at", OffsetDateTime.class);
        OffsetDateTime closesAt = room.get("participation_closes_at", OffsetDateTime.class);
        OffsetDateTime evaluationStartsAt = room.get("evaluation_starts_at", OffsetDateTime.class);
        return !at.isBefore(opensAt) && at.isBefore(closesAt) && at.isBefore(evaluationStartsAt);
    }

    private int countOccupied(UUID roomId, UUID ownerAccountId) {
        String ownerCondition = ownerAccountId == null ? "" : " and owner_account_id = ?";
        Object[] bindings = ownerAccountId == null
                ? new Object[] {roomId}
                : new Object[] {roomId, ownerAccountId};
        Number count = (Number) dsl.fetchValue(
                "select count(*) from competition.participations "
                        + "where room_id = ? "
                        + "and status not in ('WITHDRAWN'::competition.participation_status, "
                        + "'EXPELLED'::competition.participation_status)"
                        + ownerCondition,
                bindings);
        return count.intValue();
    }

    private boolean aliasExists(UUID roomId, String alias) {
        return Boolean.TRUE.equals(dsl.fetchValue(
                "select exists(select 1 from competition.participations "
                        + "where room_id = ? and anonymous_alias = ?)",
                roomId,
                alias));
    }

    private int projectedExecutionCount(UUID ownerAccountId) {
        Number count = (Number) dsl.fetchValue(
                "select ("
                        + "(select count(*) from bot.bots active "
                        + "where active.owner_account_id = ? "
                        + "and active.lifecycle_status = 'RUNNING'::bot.lifecycle_status "
                        + "and active.deleted_at is null "
                        + "and not exists(select 1 from competition.participations waiting "
                        + "where waiting.bot_id = active.id "
                        + "and waiting.status = 'REGISTERED'::competition.participation_status "
                        + "and waiting.evaluation_started_at is null)) "
                        + "+ (select count(*) from competition.participations reserved "
                        + "join bot.bots reserved_bot on reserved_bot.id = reserved.bot_id "
                        + "where reserved.owner_account_id = ? "
                        + "and reserved.status = 'REGISTERED'::competition.participation_status "
                        + "and reserved.evaluation_started_at is null and reserved_bot.deleted_at is null))",
                ownerAccountId,
                ownerAccountId);
        return count.intValue();
    }

    private boolean validProvisionedBot(
            UUID botId,
            UUID ownerAccountId,
            OffsetDateTime createdAt,
            OffsetDateTime executionEligibleFrom) {
        if (botId == null) {
            return false;
        }
        return Boolean.TRUE.equals(dsl.fetchValue(
                "select exists(select 1 from bot.bots b "
                        + "where b.id = ? and b.owner_account_id = ? "
                        + "and b.lifecycle_status = 'RUNNING'::bot.lifecycle_status "
                        + "and b.created_at = ?::timestamptz "
                        + "and b.execution_eligible_from = ?::timestamptz "
                        + "and b.started_at is null and b.deleted_at is null "
                        + "and not exists(select 1 from competition.participations p where p.bot_id = b.id))",
                botId,
                ownerAccountId,
                createdAt,
                executionEligibleFrom));
    }

    private static RoomParticipationAdmissionOutcome rejected(
            RoomParticipationAdmissionFailure failure) {
        return RoomParticipationAdmissionOutcome.rejected(failure);
    }
}

package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.RoomBotProvisioningAction;
import com.idea2strategy.backend.application.competition.RoomBotLaunchRules;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmission;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionContext;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionFailure;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionOutcome;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionPort;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionRequest;
import com.idea2strategy.backend.application.competition.RoomSubmissionSchedule;
import com.idea2strategy.backend.application.competition.RoomSubmissionTiming;
import com.idea2strategy.backend.domain.competition.CompetitionType;
import com.idea2strategy.backend.domain.competition.RoomStatus;
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
        Record account = dsl.fetchOne(
                "select lifecycle_status::text as lifecycle_status, created_at from identity.accounts where id = ? for update",
                request.ownerAccountId());
        if (account == null || !"ACTIVE".equals(account.get("lifecycle_status", String.class))) {
            return rejected(RoomParticipationAdmissionFailure.ACCOUNT_INELIGIBLE);
        }

        Record room = dsl.fetchOne(
                "select r.competition_type::text as competition_type, r.status::text as room_status, "
                        + "r.access_type::text as access_type, "
                        + "rules.bot_participation_limit, rules.per_account_bot_limit, "
                        + "rules.eligibility_document, rules.market_scope_document, "
                        + "rules.initial_cash_amount, rules.fee_policy_id, "
                        + "rules.buying_power_buffer_policy_id, rules.precision_rules_version, "
                        + "schedule.participation_opens_at, schedule.participation_closes_at, "
                        + "schedule.evaluation_starts_at, live.stopped_bot_slot_policy "
                        + "from competition.rooms r "
                        + "join competition.room_rules rules on rules.room_id = r.id "
                        + "join competition.room_schedules schedule on schedule.room_id = r.id "
                        + "left join competition.live_room_rules live on live.room_id = r.id "
                        + "where r.id = ? for update of r",
                request.roomId());
        var submissionTiming = submissionTiming(room, request.admittedAt());
        if (submissionTiming == null) {
            return rejected(RoomParticipationAdmissionFailure.ROOM_NOT_JOINABLE);
        }
        if (!eligibleAccount(account, room, request.admittedAt())) {
            return rejected(RoomParticipationAdmissionFailure.ACCOUNT_INELIGIBLE);
        }
        UUID invitationGrantId = null;
        if ("SECRET".equals(room.get("access_type", String.class))) {
            Record grant = dsl.fetchOne(
                    "select id from competition.room_invitations "
                            + "where room_id = ? and claimed_by_account_id = ? "
                            + "and admitted_participation_id is null and revoked_at is null "
                            + "and expires_at > ?::timestamptz "
                            + "order by claimed_at, id limit 1 for update",
                    request.roomId(),
                    request.ownerAccountId(),
                    request.admittedAt().atOffset(ZoneOffset.UTC));
            if (grant == null) {
                return rejected(RoomParticipationAdmissionFailure.ROOM_NOT_JOINABLE);
            }
            invitationGrantId = grant.get("id", UUID.class);
        }

        String stoppedBotSlotPolicy = room.get("stopped_bot_slot_policy", String.class);
        int roomCount = countOccupied(request.roomId(), null, stoppedBotSlotPolicy);
        if (roomCount >= room.get("bot_participation_limit", Integer.class)) {
            return rejected(RoomParticipationAdmissionFailure.ROOM_CAPACITY_REACHED);
        }
        int accountRoomCount = countOccupied(request.roomId(), request.ownerAccountId(), stoppedBotSlotPolicy);
        if (accountRoomCount >= room.get("per_account_bot_limit", Integer.class)) {
            return rejected(RoomParticipationAdmissionFailure.ACCOUNT_ROOM_LIMIT_REACHED);
        }
        if (aliasExists(request.roomId(), request.anonymousAlias())) {
            return rejected(RoomParticipationAdmissionFailure.ANONYMOUS_ALIAS_CONFLICT);
        }
        if (projectedExecutionCount(request.ownerAccountId()) >= ACCOUNT_EXECUTION_LIMIT) {
            return rejected(RoomParticipationAdmissionFailure.ACCOUNT_EXECUTION_LIMIT_REACHED);
        }

        OffsetDateTime evaluationStartsAt = room.get("evaluation_starts_at", OffsetDateTime.class);
        OffsetDateTime admittedAt = request.admittedAt().atOffset(ZoneOffset.UTC);
        OffsetDateTime executionEligibleFrom = admittedAt.isAfter(evaluationStartsAt)
                ? admittedAt
                : evaluationStartsAt;
        var context = new RoomParticipationAdmissionContext(
                request.roomId(),
                request.ownerAccountId(),
                request.admittedAt(),
                executionEligibleFrom.toInstant(),
                submissionTiming,
                new RoomBotLaunchRules(
                        room.get("initial_cash_amount", java.math.BigDecimal.class),
                        room.get("fee_policy_id", UUID.class),
                        room.get("buying_power_buffer_policy_id", UUID.class),
                        room.get("precision_rules_version", String.class)));
        UUID botId = provisioningAction.provision(context);
        if (!validProvisionedBot(
                botId,
                request.ownerAccountId(),
                request.admittedAt().atOffset(ZoneOffset.UTC),
                executionEligibleFrom)) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return rejected(RoomParticipationAdmissionFailure.PROVISIONED_BOT_INVALID);
        }
        if (!botMatchesMarketScope(botId, room.get("market_scope_document", org.jooq.JSONB.class))) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return rejected(RoomParticipationAdmissionFailure.MARKET_SCOPE_MISMATCH);
        }

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
        if (invitationGrantId != null) {
            int grantsSpent = dsl.execute(
                    "update competition.room_invitations "
                            + "set admitted_participation_id = ?, revoked_at = ?::timestamptz, "
                            + "revocation_reason_code = 'ADMITTED' "
                            + "where id = ? and claimed_by_account_id = ? "
                            + "and admitted_participation_id is null and revoked_at is null",
                    request.participationId(),
                    admittedAt,
                    invitationGrantId,
                    request.ownerAccountId());
            if (grantsSpent != 1) {
                throw new IllegalStateException("Account-bound room invitation was not spent atomically");
            }
        }
        return RoomParticipationAdmissionOutcome.accepted(new RoomParticipationAdmission(
                request.participationId(),
                request.roomId(),
                botId,
                request.ownerAccountId(),
                request.anonymousAlias(),
                request.admittedAt()));
    }

    private static RoomSubmissionTiming submissionTiming(Record room, java.time.Instant at) {
        if (room == null) {
            return null;
        }
        var schedule = new RoomSubmissionSchedule(
                CompetitionType.valueOf(room.get("competition_type", String.class)),
                RoomStatus.valueOf(room.get("room_status", String.class)),
                room.get("participation_opens_at", OffsetDateTime.class).toInstant(),
                room.get("evaluation_starts_at", OffsetDateTime.class).toInstant(),
                room.get("participation_closes_at", OffsetDateTime.class).toInstant());
        return schedule.timingAt(at).orElse(null);
    }

    private int countOccupied(UUID roomId, UUID ownerAccountId, String stoppedBotSlotPolicy) {
        String ownerCondition = ownerAccountId == null ? "" : " and owner_account_id = ?";
        String releasedCondition = "RELEASE_SLOT".equals(stoppedBotSlotPolicy)
                ? " and status not in ('WITHDRAWN'::competition.participation_status, 'EXPELLED'::competition.participation_status)"
                : "";
        Object[] bindings = ownerAccountId == null
                ? new Object[] {roomId}
                : new Object[] {roomId, ownerAccountId};
        Number count = (Number) dsl.fetchValue(
                "select count(*) from competition.participations "
                        + "where room_id = ? "
                        + releasedCondition
                        + ownerCondition,
                bindings);
        return count.intValue();
    }

    private boolean eligibleAccount(Record account, Record room, java.time.Instant admittedAt) {
        org.jooq.JSONB eligibility = room.get("eligibility_document", org.jooq.JSONB.class);
        if (eligibility == null || "{}".equals(eligibility.data())) {
            return true;
        }
        return Boolean.TRUE.equals(dsl.fetchValue(
                "select jsonb_typeof(?::jsonb) = 'object' "
                        + "and (?::jsonb - 'minimumAccountAgeDays' - 'minimumAccountState') = '{}'::jsonb "
                        + "and coalesce(?::jsonb->>'minimumAccountState', 'ACTIVE') = 'ACTIVE' "
                        + "and case when jsonb_exists(?::jsonb, 'minimumAccountAgeDays') "
                        + "then jsonb_typeof(?::jsonb->'minimumAccountAgeDays') = 'number' "
                        + "and (?::jsonb->>'minimumAccountAgeDays')::int >= 0 "
                        + "and ?::timestamptz <= ?::timestamptz - make_interval(days => (?::jsonb->>'minimumAccountAgeDays')::int) "
                        + "else true end",
                eligibility.data(), eligibility.data(), eligibility.data(), eligibility.data(),
                eligibility.data(), eligibility.data(), account.get("created_at", OffsetDateTime.class),
                admittedAt.atOffset(ZoneOffset.UTC), eligibility.data()));
    }

    private boolean botMatchesMarketScope(UUID botId, org.jooq.JSONB scope) {
        if (scope == null || "{}".equals(scope.data())) {
            return true;
        }
        return Boolean.TRUE.equals(dsl.fetchValue(
                "select case "
                        + "when (?::jsonb - 'market' - 'exchangeMics') <> '{}'::jsonb then false "
                        + "when jsonb_exists(?::jsonb, 'market') and ?::jsonb->>'market' <> 'US' then false "
                        + "else exists(select 1 from bot.bot_partitions partition "
                        + "join bot.flows flow on flow.partition_id = partition.id "
                        + "join bot.flow_instruments selected on selected.flow_id = flow.id "
                        + "where partition.bot_id = ?) "
                        + "and not exists(select 1 from bot.bot_partitions partition "
                        + "join bot.flows flow on flow.partition_id = partition.id "
                        + "join bot.flow_instruments selected on selected.flow_id = flow.id "
                        + "left join market_data.instruments instrument on instrument.id = selected.instrument_id "
                        + "where partition.bot_id = ? and (instrument.id is null "
                        + "or (jsonb_exists(?::jsonb, 'market') and (instrument.currency_code <> 'USD' or instrument.primary_exchange_mic not in ('XNAS','XNYS','ARCX','BATS','IEXG'))) "
                        + "or (jsonb_exists(?::jsonb, 'exchangeMics') and not jsonb_exists(?::jsonb->'exchangeMics', trim(instrument.primary_exchange_mic))))) end",
                scope.data(), scope.data(), scope.data(), botId, botId, scope.data(), scope.data(), scope.data()));
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

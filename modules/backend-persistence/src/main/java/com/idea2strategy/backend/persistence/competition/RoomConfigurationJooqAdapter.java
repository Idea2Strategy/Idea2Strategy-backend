package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.RoomConfigurationPort;
import com.idea2strategy.backend.application.competition.RoomConfigurationUpdate;
import com.idea2strategy.backend.application.competition.RoomConfigurationUpdateOutcome;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RoomConfigurationJooqAdapter implements RoomConfigurationPort {
    private final DSLContext dsl;

    public RoomConfigurationJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional
    public RoomConfigurationUpdateOutcome update(RoomConfigurationUpdate update) {
        var room = dsl.fetchOne(
                "select r.access_type::text as access_type, r.status::text as status, "
                        + "s.recruitment_opens_at "
                        + "from competition.rooms r "
                        + "join competition.room_schedules s on s.room_id = r.id "
                        + "where r.id = ? and r.organizer_type = 'USER' "
                        + "and r.creator_account_id = ? for update of r",
                update.roomId(), update.creatorAccountId());
        if (room == null) {
            return RoomConfigurationUpdateOutcome.NOT_FOUND_OR_NOT_OWNED;
        }
        if (!update.accessType().name().equals(room.get("access_type", String.class))) {
            return RoomConfigurationUpdateOutcome.ACCESS_TYPE_IMMUTABLE;
        }
        OffsetDateTime recruitmentOpensAt = room.get("recruitment_opens_at", OffsetDateTime.class);
        if (!"DRAFT".equals(room.get("status", String.class))
                || !update.observedAt().isBefore(recruitmentOpensAt.toInstant())
                || !update.observedAt().isBefore(update.schedule().recruitmentOpensAt())) {
            return RoomConfigurationUpdateOutcome.RECRUITMENT_LOCKED;
        }

        OffsetDateTime observedAt = update.observedAt().atOffset(ZoneOffset.UTC);
        int roomUpdated = dsl.execute(
                "update competition.rooms set name = ? where id = ? and status = 'DRAFT'",
                update.name(), update.roomId());
        int rulesUpdated = dsl.execute(
                "update competition.room_rules set scoring_template_version_id = ?, "
                        + "initial_cash_amount = ?, bot_participation_limit = ?, per_account_bot_limit = ?, "
                        + "scoring_parameters = ?::jsonb, fee_policy_id = ?, buying_power_buffer_policy_id = ?, "
                        + "rules_hash = ?, locked_at = ?::timestamptz where room_id = ?",
                update.scoringTemplateVersionId(),
                update.initialCashAmount(),
                update.botParticipationLimit(),
                update.perAccountBotLimit(),
                update.scoringParameters(),
                update.feePolicyId(),
                update.buyingPowerBufferPolicyId(),
                update.rulesHash(),
                observedAt,
                update.roomId());
        int liveRulesUpdated = dsl.execute(
                "update competition.live_room_rules set stopped_bot_slot_policy = ?, "
                        + "minimum_operation_seconds = ?, minimum_fill_count = ? where room_id = ?",
                update.liveRules().stoppedBotSlotPolicy(),
                update.liveRules().minimumOperationSeconds(),
                update.liveRules().minimumFillCount(),
                update.roomId());
        var schedule = update.schedule();
        int scheduleUpdated = dsl.execute(
                "update competition.room_schedules set recruitment_opens_at = ?::timestamptz, "
                        + "participation_opens_at = ?::timestamptz, evaluation_starts_at = ?::timestamptz, "
                        + "participation_closes_at = ?::timestamptz, evaluation_ends_at = ?::timestamptz, "
                        + "finalization_deadline_at = ?::timestamptz, timezone_name = ? where room_id = ?",
                schedule.recruitmentOpensAt().atOffset(ZoneOffset.UTC),
                schedule.participationOpensAt().atOffset(ZoneOffset.UTC),
                schedule.evaluationStartsAt().atOffset(ZoneOffset.UTC),
                schedule.participationClosesAt().atOffset(ZoneOffset.UTC),
                schedule.evaluationEndsAt().atOffset(ZoneOffset.UTC),
                schedule.finalizationDeadlineAt().atOffset(ZoneOffset.UTC),
                schedule.timezoneName(),
                update.roomId());
        if (roomUpdated != 1 || rulesUpdated != 1 || liveRulesUpdated != 1 || scheduleUpdated != 1) {
            throw new IllegalStateException("Room configuration snapshot is incomplete");
        }
        return RoomConfigurationUpdateOutcome.UPDATED;
    }
}

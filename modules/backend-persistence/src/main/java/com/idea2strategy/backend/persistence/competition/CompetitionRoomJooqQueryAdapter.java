package com.idea2strategy.backend.persistence.competition;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.idea2strategy.backend.application.competition.CompetitionRoomQueryPort;
import com.idea2strategy.backend.domain.competition.CompetitionRoom;
import com.idea2strategy.backend.domain.competition.CompetitionType;
import com.idea2strategy.backend.domain.competition.LiveRoomRules;
import com.idea2strategy.backend.domain.competition.RoomAccessType;
import com.idea2strategy.backend.domain.competition.RoomOrganizerType;
import com.idea2strategy.backend.domain.competition.RoomSchedule;
import com.idea2strategy.backend.domain.competition.RoomStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class CompetitionRoomJooqQueryAdapter implements CompetitionRoomQueryPort {
    private final DSLContext dsl;

    public CompetitionRoomJooqQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<CompetitionRoom> findById(UUID roomId) {
        var rooms = table(name("competition", "rooms")).as("r");
        var rules = table(name("competition", "room_rules")).as("rr");
        var schedules = table(name("competition", "room_schedules")).as("rs");
        var liveRules = table(name("competition", "live_room_rules")).as("lrr");
        var id = field(name("r", "id"), UUID.class);
        var competitionType = field(name("r", "competition_type"), String.class);
        var organizerType = field(name("r", "organizer_type"), String.class);
        var creatorAccountId = field(name("r", "creator_account_id"), UUID.class);
        var createdByOperatorId = field(name("r", "created_by_operator_id"), UUID.class);
        var roomName = field(name("r", "name"), String.class);
        var accessType = field(name("r", "access_type"), String.class);
        var status = field(name("r", "status"), String.class);
        var createdAt = field(name("r", "created_at"), OffsetDateTime.class);
        var scoringTemplateVersionId = field(name("rr", "scoring_template_version_id"), UUID.class);
        var initialCashAmount = field(name("rr", "initial_cash_amount"), BigDecimal.class);
        var currencyCode = field(name("rr", "currency_code"), String.class);
        var botParticipationLimit = field(name("rr", "bot_participation_limit"), Integer.class);
        var perAccountBotLimit = field(name("rr", "per_account_bot_limit"), Integer.class);
        var eligibilityDocument = field(name("rr", "eligibility_document"), JSONB.class);
        var marketScopeDocument = field(name("rr", "market_scope_document"), JSONB.class);
        var scoringParameters = field(name("rr", "scoring_parameters"), JSONB.class);
        var feePolicyId = field(name("rr", "fee_policy_id"), UUID.class);
        var slippageRateBps = field(name("rr", "slippage_rate_bps"), Integer.class);
        var bufferPolicyId = field(name("rr", "buying_power_buffer_policy_id"), UUID.class);
        var precisionRulesVersion = field(name("rr", "precision_rules_version"), String.class);
        var rulesHash = field(name("rr", "rules_hash"), String.class);
        var lockedAt = field(name("rr", "locked_at"), OffsetDateTime.class);
        var recruitmentOpensAt = field(name("rs", "recruitment_opens_at"), OffsetDateTime.class);
        var participationOpensAt = field(name("rs", "participation_opens_at"), OffsetDateTime.class);
        var evaluationStartsAt = field(name("rs", "evaluation_starts_at"), OffsetDateTime.class);
        var participationClosesAt = field(name("rs", "participation_closes_at"), OffsetDateTime.class);
        var evaluationEndsAt = field(name("rs", "evaluation_ends_at"), OffsetDateTime.class);
        var finalizationDeadlineAt = field(name("rs", "finalization_deadline_at"), OffsetDateTime.class);
        var timezoneName = field(name("rs", "timezone_name"), String.class);
        var stoppedBotSlotPolicy = field(name("lrr", "stopped_bot_slot_policy"), String.class);
        var minimumOperationSeconds = field(name("lrr", "minimum_operation_seconds"), Long.class);
        var minimumFillCount = field(name("lrr", "minimum_fill_count"), Integer.class);

        return dsl.select(
                        id,
                        competitionType,
                        organizerType,
                        creatorAccountId,
                        createdByOperatorId,
                        roomName,
                        accessType,
                        status,
                        createdAt,
                        scoringTemplateVersionId,
                        initialCashAmount,
                        currencyCode,
                        botParticipationLimit,
                        perAccountBotLimit,
                        eligibilityDocument,
                        marketScopeDocument,
                        scoringParameters,
                        feePolicyId,
                        slippageRateBps,
                        bufferPolicyId,
                        precisionRulesVersion,
                        rulesHash,
                        lockedAt,
                        recruitmentOpensAt,
                        participationOpensAt,
                        evaluationStartsAt,
                        participationClosesAt,
                        evaluationEndsAt,
                        finalizationDeadlineAt,
                        timezoneName,
                        stoppedBotSlotPolicy,
                        minimumOperationSeconds,
                        minimumFillCount)
                .from(rooms)
                .join(rules)
                .on(id.eq(field(name("rr", "room_id"), UUID.class)))
                .join(schedules)
                .on(id.eq(field(name("rs", "room_id"), UUID.class)))
                .leftJoin(liveRules)
                .on(id.eq(field(name("lrr", "room_id"), UUID.class)))
                .where(id.eq(roomId))
                .fetchOptional(record -> new CompetitionRoom(
                        record.get(id),
                        CompetitionType.valueOf(record.get(competitionType)),
                        RoomOrganizerType.valueOf(record.get(organizerType)),
                        record.get(creatorAccountId),
                        record.get(createdByOperatorId),
                        record.get(roomName),
                        RoomAccessType.valueOf(record.get(accessType)),
                        RoomStatus.valueOf(record.get(status)),
                        record.get(scoringTemplateVersionId),
                        record.get(initialCashAmount),
                        record.get(currencyCode),
                        record.get(botParticipationLimit),
                        record.get(perAccountBotLimit),
                        record.get(eligibilityDocument).data(),
                        record.get(marketScopeDocument).data(),
                        record.get(scoringParameters).data(),
                        record.get(feePolicyId),
                        record.get(slippageRateBps),
                        record.get(bufferPolicyId),
                        record.get(precisionRulesVersion),
                        record.get(rulesHash),
                        record.get(lockedAt).toInstant(),
                        record.get(competitionType).equals(CompetitionType.LIVE_PAPER.name())
                                ? new LiveRoomRules(
                                        record.get(stoppedBotSlotPolicy),
                                        record.get(minimumOperationSeconds),
                                        record.get(minimumFillCount))
                                : null,
                        new RoomSchedule(
                                record.get(recruitmentOpensAt).toInstant(),
                                record.get(participationOpensAt).toInstant(),
                                record.get(evaluationStartsAt).toInstant(),
                                record.get(participationClosesAt).toInstant(),
                                record.get(evaluationEndsAt).toInstant(),
                                record.get(finalizationDeadlineAt).toInstant(),
                                record.get(timezoneName)),
                        record.get(createdAt).toInstant()));
    }
}

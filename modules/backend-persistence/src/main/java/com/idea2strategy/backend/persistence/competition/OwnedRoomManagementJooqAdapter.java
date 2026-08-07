package com.idea2strategy.backend.persistence.competition;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.competition.OwnedRoomManagementQueryPort;
import com.idea2strategy.backend.application.competition.OwnedRoomManagementView;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class OwnedRoomManagementJooqAdapter implements OwnedRoomManagementQueryPort {
    private static final TypeReference<Map<String, BigDecimal>> DECIMAL_MAP = new TypeReference<>() {};
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public OwnedRoomManagementJooqAdapter(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<OwnedRoomManagementView> findOwnedBy(UUID ownerAccountId, int limit) {
        var rooms = table(name("competition", "rooms")).as("r");
        var rules = table(name("competition", "room_rules")).as("rr");
        var schedules = table(name("competition", "room_schedules")).as("rs");
        var live = table(name("competition", "live_room_rules")).as("lr");
        var id = field(name("r", "id"), UUID.class);
        var createdAt = field(name("r", "created_at"), OffsetDateTime.class);

        List<BaseRoom> baseRooms = dsl.select(
                        id,
                        field(name("r", "name"), String.class),
                        field(name("r", "access_type"), String.class),
                        field(name("r", "status"), String.class),
                        createdAt,
                        field(name("rr", "scoring_template_version_id"), UUID.class),
                        field(name("rr", "scoring_parameters"), JSONB.class),
                        field(name("rr", "initial_cash_amount"), BigDecimal.class),
                        field(name("rr", "bot_participation_limit"), Integer.class),
                        field(name("rr", "per_account_bot_limit"), Integer.class),
                        field(name("lr", "stopped_bot_slot_policy"), String.class),
                        field(name("lr", "minimum_operation_seconds"), Long.class),
                        field(name("lr", "minimum_fill_count"), Integer.class),
                        field(name("rr", "fee_policy_id"), UUID.class),
                        field(name("rr", "buying_power_buffer_policy_id"), UUID.class),
                        field(name("rs", "recruitment_opens_at"), OffsetDateTime.class),
                        field(name("rs", "participation_opens_at"), OffsetDateTime.class),
                        field(name("rs", "evaluation_starts_at"), OffsetDateTime.class),
                        field(name("rs", "participation_closes_at"), OffsetDateTime.class),
                        field(name("rs", "evaluation_ends_at"), OffsetDateTime.class),
                        field(name("rs", "finalization_deadline_at"), OffsetDateTime.class),
                        field(name("rs", "timezone_name"), String.class))
                .from(rooms)
                .join(rules).on(id.eq(field(name("rr", "room_id"), UUID.class)))
                .join(schedules).on(id.eq(field(name("rs", "room_id"), UUID.class)))
                .join(live).on(id.eq(field(name("lr", "room_id"), UUID.class)))
                .where(field(name("r", "creator_account_id"), UUID.class).eq(ownerAccountId))
                .orderBy(createdAt.desc(), id.desc())
                .limit(limit)
                .fetch(record -> new BaseRoom(
                        record.get(id),
                        record.get(field(name("r", "name"), String.class)),
                        record.get(field(name("r", "access_type"), String.class)),
                        record.get(field(name("r", "status"), String.class)),
                        record.get(createdAt),
                        record.get(field(name("rr", "scoring_template_version_id"), UUID.class)),
                        readAdjustments(record.get(field(name("rr", "scoring_parameters"), JSONB.class))),
                        record.get(field(name("rr", "initial_cash_amount"), BigDecimal.class)),
                        record.get(field(name("rr", "bot_participation_limit"), Integer.class)),
                        record.get(field(name("rr", "per_account_bot_limit"), Integer.class)),
                        record.get(field(name("lr", "stopped_bot_slot_policy"), String.class)),
                        record.get(field(name("lr", "minimum_operation_seconds"), Long.class)),
                        record.get(field(name("lr", "minimum_fill_count"), Integer.class)),
                        record.get(field(name("rr", "fee_policy_id"), UUID.class)),
                        record.get(field(name("rr", "buying_power_buffer_policy_id"), UUID.class)),
                        record.get(field(name("rs", "recruitment_opens_at"), OffsetDateTime.class)),
                        record.get(field(name("rs", "participation_opens_at"), OffsetDateTime.class)),
                        record.get(field(name("rs", "evaluation_starts_at"), OffsetDateTime.class)),
                        record.get(field(name("rs", "participation_closes_at"), OffsetDateTime.class)),
                        record.get(field(name("rs", "evaluation_ends_at"), OffsetDateTime.class)),
                        record.get(field(name("rs", "finalization_deadline_at"), OffsetDateTime.class)),
                        record.get(field(name("rs", "timezone_name"), String.class))));

        if (baseRooms.isEmpty()) return List.of();
        List<UUID> roomIds = baseRooms.stream().map(BaseRoom::id).toList();
        Map<UUID, List<OwnedRoomManagementView.Invitation>> invitations = invitationRows(roomIds);
        Map<UUID, List<OwnedRoomManagementView.Participation>> participations = participationRows(roomIds);
        return baseRooms.stream().map(room -> room.view(
                invitations.getOrDefault(room.id(), List.of()),
                participations.getOrDefault(room.id(), List.of()))).toList();
    }

    private Map<UUID, List<OwnedRoomManagementView.Invitation>> invitationRows(List<UUID> roomIds) {
        Map<UUID, List<OwnedRoomManagementView.Invitation>> result = new LinkedHashMap<>();
        var invitations = table(name("competition", "room_invitations")).as("i");
        var roomId = field(name("i", "room_id"), UUID.class);
        dsl.select(roomId,
                        field(name("i", "id"), UUID.class),
                        field(name("i", "credential_type"), String.class),
                        field(name("i", "issued_at"), OffsetDateTime.class),
                        field(name("i", "expires_at"), OffsetDateTime.class),
                        field(name("i", "revoked_at"), OffsetDateTime.class),
                        field(name("i", "revocation_reason_code"), String.class))
                .from(invitations)
                .where(roomId.in(roomIds))
                .orderBy(field(name("i", "issued_at")).desc())
                .forEach(record -> result.computeIfAbsent(record.get(roomId), ignored -> new ArrayList<>()).add(
                        new OwnedRoomManagementView.Invitation(
                                record.get(field(name("i", "id"), UUID.class)),
                                record.get(field(name("i", "credential_type"), String.class)),
                                instant(record.get(field(name("i", "issued_at"), OffsetDateTime.class))),
                                instant(record.get(field(name("i", "expires_at"), OffsetDateTime.class))),
                                instant(record.get(field(name("i", "revoked_at"), OffsetDateTime.class))),
                                record.get(field(name("i", "revocation_reason_code"), String.class)))));
        return result;
    }

    private Map<UUID, List<OwnedRoomManagementView.Participation>> participationRows(List<UUID> roomIds) {
        Map<UUID, List<OwnedRoomManagementView.Participation>> result = new LinkedHashMap<>();
        var participations = table(name("competition", "participations")).as("p");
        var roomId = field(name("p", "room_id"), UUID.class);
        dsl.select(roomId,
                        field(name("p", "id"), UUID.class),
                        field(name("p", "bot_id"), UUID.class),
                        field(name("p", "anonymous_alias"), String.class),
                        field(name("p", "status"), String.class),
                        field(name("p", "joined_at"), OffsetDateTime.class))
                .from(participations)
                .where(roomId.in(roomIds))
                .orderBy(field(name("p", "joined_at")), field(name("p", "id")))
                .forEach(record -> result.computeIfAbsent(record.get(roomId), ignored -> new ArrayList<>()).add(
                        new OwnedRoomManagementView.Participation(
                                record.get(field(name("p", "id"), UUID.class)),
                                record.get(field(name("p", "bot_id"), UUID.class)),
                                record.get(field(name("p", "anonymous_alias"), String.class)),
                                record.get(field(name("p", "status"), String.class)),
                                instant(record.get(field(name("p", "joined_at"), OffsetDateTime.class))))));
        return result;
    }

    private Map<String, BigDecimal> readAdjustments(JSONB value) {
        try {
            return value == null ? Map.of() : objectMapper.readValue(value.data(), DECIMAL_MAP);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored scoring parameters are invalid", exception);
        }
    }

    private static java.time.Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record BaseRoom(
            UUID id, String name, String accessType, String status, OffsetDateTime createdAt,
            UUID scoringTemplateVersionId, Map<String, BigDecimal> scoringAdjustments,
            BigDecimal initialCashAmount, int botParticipationLimit, int perAccountBotLimit,
            String stoppedBotSlotPolicy, long minimumOperationSeconds, int minimumFillCount,
            UUID feePolicyId, UUID buyingPowerBufferPolicyId,
            OffsetDateTime recruitmentOpensAt, OffsetDateTime participationOpensAt,
            OffsetDateTime evaluationStartsAt, OffsetDateTime participationClosesAt,
            OffsetDateTime evaluationEndsAt, OffsetDateTime finalizationDeadlineAt, String timezoneName) {
        OwnedRoomManagementView view(
                List<OwnedRoomManagementView.Invitation> invitations,
                List<OwnedRoomManagementView.Participation> participations) {
            return new OwnedRoomManagementView(
                    id, name, accessType, status, createdAt.toInstant(), scoringTemplateVersionId,
                    scoringAdjustments, initialCashAmount, botParticipationLimit, perAccountBotLimit,
                    stoppedBotSlotPolicy, minimumOperationSeconds, minimumFillCount,
                    feePolicyId, buyingPowerBufferPolicyId, recruitmentOpensAt.toInstant(),
                    participationOpensAt.toInstant(), evaluationStartsAt.toInstant(),
                    participationClosesAt.toInstant(), evaluationEndsAt.toInstant(),
                    finalizationDeadlineAt.toInstant(), timezoneName, invitations, participations);
        }
    }
}

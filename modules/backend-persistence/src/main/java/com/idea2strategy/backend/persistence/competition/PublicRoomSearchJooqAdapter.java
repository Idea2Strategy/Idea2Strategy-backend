package com.idea2strategy.backend.persistence.competition;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.condition;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.val;

import com.idea2strategy.backend.application.competition.PublicRoomItem;
import com.idea2strategy.backend.application.competition.PublicRoomSearchPort;
import com.idea2strategy.backend.domain.competition.RoomOrganizerType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class PublicRoomSearchJooqAdapter implements PublicRoomSearchPort {
    private final DSLContext dsl;

    public PublicRoomSearchJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<PublicRoomItem> search(
            String nameQuery, Instant beforeCreatedAt, UUID beforeId, int limit) {
        var rooms = table(name("competition", "rooms")).as("r");
        var rules = table(name("competition", "room_rules")).as("rr");
        var schedules = table(name("competition", "room_schedules")).as("rs");
        var id = field(name("r", "id"), UUID.class);
        var roomName = field(name("r", "name"), String.class);
        var organizerType = field(name("r", "organizer_type"), String.class);
        var createdAt = field(name("r", "created_at"), OffsetDateTime.class);
        var recruitmentOpensAt = field(name("rs", "recruitment_opens_at"), OffsetDateTime.class);
        var participationClosesAt = field(name("rs", "participation_closes_at"), OffsetDateTime.class);
        var botParticipationLimit = field(name("rr", "bot_participation_limit"), Integer.class);
        var perAccountBotLimit = field(name("rr", "per_account_bot_limit"), Integer.class);

        Condition condition = condition(
                        "{0} = cast({1} as competition.room_access_type)",
                        field(name("r", "access_type")),
                        val("PUBLIC"))
                .and(condition(
                        "{0} = cast({1} as competition.room_status)",
                        field(name("r", "status")),
                        val("RECRUITING")));
        if (!nameQuery.isBlank()) {
            condition = condition.and(roomName.containsIgnoreCase(nameQuery));
        }
        if (beforeCreatedAt != null) {
            var cursorTime = OffsetDateTime.ofInstant(beforeCreatedAt, ZoneOffset.UTC);
            condition = condition.and(createdAt.lt(cursorTime)
                    .or(createdAt.eq(cursorTime).and(id.lt(beforeId))));
        }

        return dsl.select(
                        id,
                        roomName,
                        organizerType,
                        createdAt,
                        recruitmentOpensAt,
                        participationClosesAt,
                        botParticipationLimit,
                        perAccountBotLimit)
                .from(rooms)
                .join(rules)
                .on(id.eq(field(name("rr", "room_id"), UUID.class)))
                .join(schedules)
                .on(id.eq(field(name("rs", "room_id"), UUID.class)))
                .where(condition)
                .orderBy(createdAt.desc(), id.desc())
                .limit(limit)
                .fetch(record -> new PublicRoomItem(
                        record.get(id),
                        record.get(roomName),
                        RoomOrganizerType.valueOf(record.get(organizerType)),
                        record.get(createdAt).toInstant(),
                        record.get(recruitmentOpensAt).toInstant(),
                        record.get(participationClosesAt).toInstant(),
                        record.get(botParticipationLimit),
                        record.get(perAccountBotLimit)));
    }
}

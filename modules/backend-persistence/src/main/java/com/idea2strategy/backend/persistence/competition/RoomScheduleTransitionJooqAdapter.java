package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.RoomScheduleTransitionPort;
import com.idea2strategy.backend.application.competition.RoomScheduleTransitionReport;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RoomScheduleTransitionJooqAdapter implements RoomScheduleTransitionPort {
    private final DSLContext dsl;

    public RoomScheduleTransitionJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional
    public RoomScheduleTransitionReport advanceDue(Instant observedAt, int limit) {
        OffsetDateTime observed = utc(observedAt);
        var candidates = dsl.fetch(
                "select r.id, r.status::text as status, s.recruitment_opens_at, "
                        + "s.evaluation_starts_at, s.evaluation_ends_at "
                        + "from competition.rooms r "
                        + "join competition.room_schedules s on s.room_id = r.id "
                        + "where (r.status = 'DRAFT'::competition.room_status and s.recruitment_opens_at <= ?::timestamptz) "
                        + "or (r.status = 'RECRUITING'::competition.room_status and s.evaluation_starts_at <= ?::timestamptz) "
                        + "or (r.status = 'EVALUATING'::competition.room_status and s.evaluation_ends_at <= ?::timestamptz) "
                        + "order by case r.status::text when 'DRAFT' then s.recruitment_opens_at "
                        + "when 'RECRUITING' then s.evaluation_starts_at else s.evaluation_ends_at end, r.id "
                        + "limit ? for update of r skip locked",
                observed, observed, observed, limit);

        int transitions = 0;
        for (var candidate : candidates) {
            UUID roomId = candidate.get("id", UUID.class);
            String status = candidate.get("status", String.class);
            OffsetDateTime recruitmentOpensAt = candidate.get("recruitment_opens_at", OffsetDateTime.class);
            OffsetDateTime evaluationStartsAt = candidate.get("evaluation_starts_at", OffsetDateTime.class);
            OffsetDateTime evaluationEndsAt = candidate.get("evaluation_ends_at", OffsetDateTime.class);
            if ("DRAFT".equals(status) && !observed.isBefore(recruitmentOpensAt)) {
                transition(roomId, "DRAFT", "RECRUITING", "RECRUITMENT_OPENED", recruitmentOpensAt, observed);
                status = "RECRUITING";
                transitions++;
            }
            if ("RECRUITING".equals(status) && !observed.isBefore(evaluationStartsAt)) {
                transition(roomId, "RECRUITING", "EVALUATING", "EVALUATION_STARTED", evaluationStartsAt, observed);
                status = "EVALUATING";
                transitions++;
            }
            if ("EVALUATING".equals(status) && !observed.isBefore(evaluationEndsAt)) {
                transition(roomId, "EVALUATING", "ENDED", "EVALUATION_ENDED", evaluationEndsAt, observed);
                transitions++;
            }
        }
        return new RoomScheduleTransitionReport(observedAt, candidates.size(), transitions);
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
        Number nextSequence = (Number) dsl.fetchValue(
                "select coalesce(max(event_sequence), 0) + 1 from competition.room_events where room_id = ?",
                roomId);
        dsl.execute(
                "insert into competition.room_events "
                        + "(id, room_id, event_sequence, event_type, resulting_status, occurred_at, payload_document) "
                        + "values (?, ?, ?, ?, ?::competition.room_status, ?::timestamptz, "
                        + "jsonb_build_object('scheduledAt', ?::text, 'observedAt', ?::text))",
                UUID.randomUUID(), roomId, nextSequence.intValue(), eventType, resultingStatus, observedAt,
                scheduledAt.toInstant().toString(), observedAt.toInstant().toString());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

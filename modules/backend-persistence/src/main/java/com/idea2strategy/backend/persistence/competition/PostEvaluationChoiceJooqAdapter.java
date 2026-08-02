package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.PostEvaluationAction;
import com.idea2strategy.backend.application.competition.PostEvaluationChoice;
import com.idea2strategy.backend.application.competition.PostEvaluationChoiceAccessException;
import com.idea2strategy.backend.application.competition.PostEvaluationChoiceConflictException;
import com.idea2strategy.backend.application.competition.PostEvaluationChoicePort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PostEvaluationChoiceJooqAdapter implements PostEvaluationChoicePort {
    private final DSLContext dsl;

    public PostEvaluationChoiceJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional(readOnly = true)
    public PostEvaluationChoice findOwned(UUID roomId, UUID participationId, UUID ownerAccountId) {
        Record record = dsl.fetchOne(
                "select p.post_room_action::text as action, p.action_recorded_at, p.action_locked_at "
                        + "from competition.participations p where p.id = ? and p.room_id = ? "
                        + "and p.owner_account_id = ?",
                participationId, roomId, ownerAccountId);
        if (record == null) {
            throw new PostEvaluationChoiceAccessException();
        }
        return choice(roomId, participationId, record);
    }

    @Override
    @Transactional
    public PostEvaluationChoice updateOwned(
            UUID roomId,
            UUID participationId,
            UUID ownerAccountId,
            PostEvaluationAction action,
            Instant recordedAt) {
        Record record = dsl.fetchOne(
                "select r.status::text as room_status, s.evaluation_starts_at, s.evaluation_ends_at, "
                        + "p.status::text as participation_status, p.post_room_action::text as action, "
                        + "p.action_recorded_at, p.action_locked_at "
                        + "from competition.rooms r "
                        + "join competition.room_schedules s on s.room_id = r.id "
                        + "join competition.participations p on p.room_id = r.id "
                        + "where r.id = ? and p.id = ? and p.owner_account_id = ? for update of r, p",
                roomId, participationId, ownerAccountId);
        if (record == null) {
            throw new PostEvaluationChoiceAccessException();
        }
        requireMutableEvaluation(record, recordedAt);

        String storedAction = storedAction(action);
        if (storedAction.equals(record.get("action", String.class))) {
            return choice(roomId, participationId, record);
        }

        String priorAction = record.get("action", String.class);
        dsl.execute(
                "update competition.participations set post_room_action = "
                        + "?::competition.post_room_action, action_recorded_at = ?::timestamptz where id = ?",
                storedAction, utc(recordedAt), participationId);
        recordChoiceEvent(participationId, roomId, ownerAccountId, priorAction, action, recordedAt);
        return new PostEvaluationChoice(roomId, participationId, action, recordedAt, null);
    }

    private static void requireMutableEvaluation(Record record, Instant recordedAt) {
        Instant startsAt = record.get("evaluation_starts_at", OffsetDateTime.class).toInstant();
        Instant endsAt = record.get("evaluation_ends_at", OffsetDateTime.class).toInstant();
        if (!"EVALUATING".equals(record.get("room_status", String.class))
                || !"EVALUATING".equals(record.get("participation_status", String.class))
                || recordedAt.isBefore(startsAt)
                || !recordedAt.isBefore(endsAt)) {
            throw new PostEvaluationChoiceConflictException(
                    "Post-evaluation action can be changed only during the official evaluation window");
        }
        if (record.get("action_locked_at", OffsetDateTime.class) != null) {
            throw new PostEvaluationChoiceConflictException("Post-evaluation action is already locked");
        }
    }

    private void recordChoiceEvent(
            UUID participationId,
            UUID roomId,
            UUID actorAccountId,
            String priorAction,
            PostEvaluationAction action,
            Instant recordedAt) {
        int sequence = ((Number) dsl.fetchValue(
                        "select coalesce(max(event_sequence), 0) + 1 "
                                + "from competition.participation_events where participation_id = ?",
                        participationId))
                .intValue();
        dsl.execute(
                "insert into competition.participation_events "
                        + "(id, participation_id, event_sequence, event_type, occurred_at, payload_document) "
                        + "values (?, ?, ?, 'POST_EVALUATION_ACTION_RECORDED', ?::timestamptz, "
                        + "jsonb_build_object('roomId', ?::text, 'actorAccountId', ?::text, "
                        + "'previousAction', ?::text, 'action', ?::text))",
                UUID.randomUUID(), participationId, sequence, utc(recordedAt), roomId, actorAccountId,
                priorAction, action.name());
    }

    private static PostEvaluationChoice choice(UUID roomId, UUID participationId, Record record) {
        String storedAction = record.get("action", String.class);
        OffsetDateTime recordedAt = record.get("action_recorded_at", OffsetDateTime.class);
        OffsetDateTime lockedAt = record.get("action_locked_at", OffsetDateTime.class);
        return new PostEvaluationChoice(
                roomId,
                participationId,
                apiAction(storedAction),
                recordedAt == null ? null : recordedAt.toInstant(),
                lockedAt == null ? null : lockedAt.toInstant());
    }

    private static String storedAction(PostEvaluationAction action) {
        return action == PostEvaluationAction.STOP_AFTER_EVALUATION ? "STOP" : action.name();
    }

    private static PostEvaluationAction apiAction(String storedAction) {
        if (storedAction == null) {
            return null;
        }
        return "STOP".equals(storedAction)
                ? PostEvaluationAction.STOP_AFTER_EVALUATION
                : PostEvaluationAction.valueOf(storedAction);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
